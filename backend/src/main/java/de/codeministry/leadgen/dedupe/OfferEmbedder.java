/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.dedupe;

import de.codeministry.leadgen.config.ConfigRegistry;
import de.codeministry.leadgen.config.model.PipelineConfig;
import de.codeministry.leadgen.llm.EmbeddingModels;
import de.codeministry.leadgen.llm.LlmBudget;
import de.codeministry.leadgen.llm.Vectors;
import java.util.List;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Gives every offer in the deduplication window a vector, once.
 *
 * <p>The vector is what lets the two similarity strategies compare adverts that are not
 * identical — the same project through two portals, written up by two people. Everything
 * about how it is compared belongs to {@link DeduplicationService}; this class only fills
 * the column.
 *
 * <p><b>`llm.models.embedding` is read and nothing stands in for it.</b> There is no falling
 * back to the scoring model the way the ingest fallback does: a chat model is not an
 * embedding model, and asking one for a vector produces an error rather than a worse answer.
 * Unset therefore means the similarity strategies do not run at all, which is the same thing
 * a fresh clone has always done.
 */
@Slf4j
@Component
public class OfferEmbedder {

    /**
     * The width `V25` states on the column. Delegated to {@link Vectors#DIMENSIONS}, which is
     * where the number and the argument for truncating now live, because a second embedding
     * stage writes to a second column of the same width and two copies of this would disagree
     * exactly once. Kept as a constant here so the callers and the tests that name it do not
     * all have to move.
     */
    public static final int DIMENSIONS = Vectors.DIMENSIONS;

    /**
     * How many adverts go into one request. Large enough that a nightly pass is a handful of
     * calls, small enough that one refused request does not lose an evening's work.
     */
    private static final int BATCH = 32;

    /**
     * The advert's opening, not the whole of it. What is being compared is whether two
     * postings are the same project; a page of boilerplate about the client's culture makes
     * two different projects from the same agency look alike rather than less alike.
     */
    static final int DESCRIPTION_CHARS = 600;

    /** So a truncating configuration says so once per process and not once per advert. */
    private final java.util.concurrent.atomic.AtomicBoolean saidTruncating =
            new java.util.concurrent.atomic.AtomicBoolean();

    private final ConfigRegistry config;
    private final EmbeddingModels models;
    private final LlmBudget budget;
    private final JdbcClient jdbc;

    OfferEmbedder(ConfigRegistry config, EmbeddingModels models, LlmBudget budget, DataSource dataSource) {
        this.config = config;
        this.models = models;
        this.budget = budget;
        this.jdbc = JdbcClient.create(dataSource);
    }

    /**
     * The model this configuration would embed with, or nothing.
     *
     * <p>Public because the pass has to know whether a similarity comparison is possible
     * before it decides what to report, and asking twice is cheaper than guessing.
     */
    public String model() {
        PipelineConfig.Llm llm = config.snapshot().application().llm();
        if (llm == null || llm.models() == null) {
            return null;
        }
        String model = llm.models().embedding();
        return model == null || model.isBlank() ? null : model;
    }

    /**
     * Embeds everything on the working list that has no current vector.
     *
     * <p>Already-merged offers are skipped: the exact pass runs first and has resolved them,
     * and a vector for a row that is already attached to a primary buys nothing.
     *
     * <p><b>Archived offers are skipped too, and that scopes the similarity strategies to the
     * working list.</b> A row with no vector is invisible to both of them, because they compare
     * only rows embedded by the same model. The exact fingerprint is not scoped that way, so
     * the two strategies see different populations on purpose: attaching a fresh offer to an
     * archived primary would put it behind something that is already history, since
     * `keep_first_seen_as_primary` makes the older row the primary. In a nightly run the
     * difference is nothing at all, because archiving happens after this stage and there is
     * nothing archived yet to skip. On a backfilled corpus it is the whole cost: measured on
     * 13240 offers of which 13232 were archived, the window held 11437 rows to embed and 8 of
     * them were still on the working list — 357 of 358 requests, and a day's budget, spent on
     * adverts nobody will see again.
     *
     * @return how many offers were given a vector, 0 when nothing could be.
     */
    public int embed(int ttlDays) {
        String model = model();
        if (model == null) {
            return 0;
        }
        PipelineConfig.Llm llm = config.snapshot().application().llm();
        var embeddings = models.of(llm, model);
        if (embeddings.isEmpty()) {
            return 0;
        }

        List<Pending> pending = pending(ttlDays, model);
        if (pending.isEmpty()) {
            return 0;
        }

        int written = 0;
        for (int from = 0; from < pending.size(); from += BATCH) {
            List<Pending> batch = pending.subList(from, Math.min(from + BATCH, pending.size()));
            // One request carries thirty-two adverts and counts as one call, because the
            // number in the configuration says calls and an exception to that would live
            // only in the code rather than beside the number a person reads.
            if (!budget.take()) {
                break;
            }
            int done = embed(embeddings.get(), model, batch);
            if (done < 0) {
                // A refusal that is about the configuration rather than about this batch, so
                // the next batch would be refused the same way.
                break;
            }
            written += done;
        }
        log.info("Deduplication: {} of {} offers embedded with '{}'", written, pending.size(), model);
        return written;
    }

    /**
     * One request, or -1 when the answer says this configuration cannot be used at all.
     */
    private int embed(EmbeddingModel embeddings, String model, List<Pending> batch) {
        List<String> texts = batch.stream().map(Pending::text).toList();
        List<float[]> vectors;
        try {
            vectors = embeddings.call(new EmbeddingRequest(texts, null)).getResults().stream()
                    .map(result -> result.getOutput())
                    .toList();
        } catch (RuntimeException e) {
            log.warn("Embedding {} offers failed: {}", batch.size(), e.getMessage());
            return 0;
        }
        if (vectors.size() != batch.size()) {
            log.warn("Asked for {} embeddings and got {}; the batch is skipped", batch.size(), vectors.size());
            return 0;
        }

        int written = 0;
        for (int index = 0; index < batch.size(); index++) {
            float[] vector = vectors.get(index);
            if (vector.length < DIMENSIONS) {
                log.warn(
                        "Model '{}' returns {}-dimensional vectors and the offer.embedding column holds {}."
                                + " Configure a model of at least {} dimensions in llm.models.embedding,"
                                + " or nothing is compared.",
                        model,
                        vector.length,
                        DIMENSIONS,
                        DIMENSIONS);
                return -1;
            }
            if (vector.length > DIMENSIONS && saidTruncating.compareAndSet(false, true)) {
                log.info(
                        "Model '{}' returns {} dimensions; the leading {} are stored, which is the widest"
                                + " vector pgvector will index.",
                        model,
                        vector.length,
                        DIMENSIONS);
            }
            jdbc.sql("UPDATE offer SET embedding = CAST(:vector AS vector), embedding_model = :model WHERE id = :id")
                    .param("vector", literal(narrowed(vector)))
                    .param("model", model)
                    .param("id", batch.get(index).id())
                    .update();
            written++;
        }
        return written;
    }

    private List<Pending> pending(int ttlDays, String model) {
        return jdbc.sql(
                        """
                    SELECT id, title, location, description
                      FROM offer
                     WHERE ingested_at >= now() - make_interval(days => :ttl)
                       AND duplicate_of_id IS NULL
                       AND archived_at IS NULL
                       AND (embedding IS NULL OR embedding_model IS DISTINCT FROM :model)
                     ORDER BY id
                    """)
                .param("ttl", ttlDays)
                .param("model", model)
                .query((rs, row) -> new Pending(
                        rs.getLong("id"),
                        text(rs.getString("title"), rs.getString("location"), rs.getString("description"))))
                .list();
    }

    /**
     * What is embedded: the title, the location and the advert's opening.
     *
     * <p>Those three are what exists at this point in the pipeline — everything else comes
     * from enrichment, which runs after deduplication. The location is deliberately in: it is
     * the one field that cost the exact fingerprint 53 correct merges, because "Nürnberg" and
     * "Remote und Nürnberg" are the same place written twice and only the same string once.
     */
    static String text(String title, String location, String description) {
        StringBuilder text = new StringBuilder(title == null ? "" : title.strip());
        if (location != null && !location.isBlank()) {
            text.append('\n').append(location.strip());
        }
        if (description != null && !description.isBlank()) {
            String opening = description.strip();
            text.append('\n')
                    .append(opening.length() <= DESCRIPTION_CHARS ? opening : opening.substring(0, DESCRIPTION_CHARS));
        }
        return text.toString();
    }

    /** {@link Vectors#narrowed} — kept so the tests that name it here keep naming it here. */
    static float[] narrowed(float[] vector) {
        return Vectors.narrowed(vector);
    }

    /** {@link Vectors#literal}. */
    static String literal(float[] vector) {
        return Vectors.literal(vector);
    }

    private record Pending(long id, String text) {}
}
