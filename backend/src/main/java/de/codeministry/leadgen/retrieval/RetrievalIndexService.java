/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.retrieval;

import de.codeministry.leadgen.config.ConfigRegistry;
import de.codeministry.leadgen.config.model.PipelineConfig;
import de.codeministry.leadgen.llm.EmbeddingModels;
import de.codeministry.leadgen.llm.LlmBudget;
import de.codeministry.leadgen.llm.Vectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Writes {@code offer.retrieval_embedding}: one vector per offer, over the whole de-furnitured
 * advert.
 *
 * <p>Which column, which text and why the stage sits behind {@code SCORE} are all in this
 * package's {@code package-info}. Two things about the shape of the pass itself:
 *
 * <p><b>Deliberately not {@code @Transactional}.</b> The same reason {@code EnrichmentService},
 * {@code ContentService}, {@code FieldsService} and {@code ScoringService} each write down:
 * computing a vector is an HTTP call, a transaction held open across a few hundred of them is
 * held open for minutes, and every offer it had touched would sit under a write lock with the
 * filter stage queued behind it.
 *
 * <p><b>Nothing is written as embedded that was not embedded.</b> A refused budget breaks the
 * loop, the rest of the population keeps {@code retrieval_embedded_at IS NULL}, and the next run
 * picks them up — the shape every other stage already has for a model that does not answer.
 */
@Slf4j
@Service
public class RetrievalIndexService {

    /**
     * How many adverts go into one request. {@code OfferEmbedder}'s batch, so the two stages
     * cost the same per offer and one number explains both.
     */
    private static final int BATCH = 32;

    /**
     * Due is three questions and not one.
     *
     * <p>Never embedded; embedded by a different model, because two models are two spaces and a
     * row from another one has to be re-embedded rather than compared; and <b>embedded before
     * the advert last changed</b>, which is the part {@code OfferEmbedder} has no equivalent of.
     * The content stage rewrites {@code content_blocks} when a portal changes its markup, and a
     * vector of the old text under an unchanged model name is exactly the kind of staleness a
     * model comparison cannot see.
     *
     * <p>Scoped to the working list for the reason the dedupe embedder measured: on a corpus of
     * 13240 offers of which 13232 were archived, an unscoped window held 11437 rows to embed and
     * 8 of them were still on the working list. The vector survives archiving — nothing nulls it
     * — so the searchable corpus still grows with every night.
     */
    private static final String DUE = """
        SELECT id, title, location, content_blocks, full_text
        FROM offer
        WHERE status = 'PASSED' AND duplicate_of_id IS NULL AND archived_at IS NULL
          AND (full_text IS NOT NULL OR content_blocks IS NOT NULL)
          AND (retrieval_embedded_at IS NULL
               OR retrieval_embedding_model IS DISTINCT FROM :model
               OR (content_at IS NOT NULL AND content_at > retrieval_embedded_at))
        ORDER BY id
        """;

    private static final String RECORD = """
        UPDATE offer
           SET retrieval_embedding = CAST(:vector AS vector),
               retrieval_embedding_model = :model,
               retrieval_embedded_at = now()
         WHERE id = :id
        """;

    /** So a truncating configuration says so once per process and not once per advert. */
    private final AtomicBoolean saidTruncating = new AtomicBoolean();

    private final ConfigRegistry config;
    private final EmbeddingModels models;
    private final LlmBudget budget;
    private final JdbcClient jdbc;

    RetrievalIndexService(ConfigRegistry config, EmbeddingModels models, LlmBudget budget, DataSource dataSource) {
        this.config = config;
        this.models = models;
        this.budget = budget;
        this.jdbc = JdbcClient.create(dataSource);
    }

    /**
     * The embedding model this configuration would index with, or null.
     *
     * <p>{@code llm.models.embedding}, the same key deduplication reads. A {@code models.retrieval}
     * key would be a second allowlist, a second entry in the run history and a second select in
     * the header — which is what a {@code models.content} and a {@code models.fields} key were
     * each refused for.
     */
    String model() {
        PipelineConfig.Llm llm = config.snapshot().application().llm();
        if (llm == null || llm.models() == null) {
            return null;
        }
        String model = llm.models().embedding();
        return model == null || model.isBlank() ? null : model;
    }

    /**
     * One pass over everything due.
     *
     * <p>Switched off, or without an embedding model, this writes nothing and says so once. The
     * column then stays empty, which is what a fresh clone has: semantic search does not exist
     * and everything else runs unchanged. That is rules before model, on this stage.
     */
    public RetrievalReport run() {
        PipelineConfig.Retrieval retrieval = config.snapshot().application().retrieval();
        if (retrieval == null || !retrieval.enabled()) {
            return RetrievalReport.skipped();
        }
        String model = model();
        if (model == null) {
            log.info("Retrieval: llm.models.embedding is not set, so no advert is indexed");
            return RetrievalReport.skipped();
        }
        PipelineConfig.Llm llm = config.snapshot().application().llm();
        var embeddings = models.of(llm, model);
        if (embeddings.isEmpty()) {
            return RetrievalReport.skipped();
        }

        List<Pending> due = due(model);
        if (due.isEmpty()) {
            return new RetrievalReport(0, 0, 0, model);
        }

        int embedded = 0;
        int requests = 0;
        for (int from = 0; from < due.size(); from += BATCH) {
            List<Pending> batch = due.subList(from, Math.min(from + BATCH, due.size()));
            // One request carries a batch and counts as one call, because the number in the
            // configuration says calls. A refusal leaves the rest due rather than half-written.
            if (!budget.take()) {
                log.info(
                        "Retrieval: the day's llm.budget is spent after {} of {} offers;"
                                + " the rest stay due for the next run",
                        embedded,
                        due.size());
                break;
            }
            requests++;
            int written = embed(embeddings.get(), model, batch);
            if (written < 0) {
                // About the configuration rather than about this batch, so the next batch
                // would be refused the same way.
                break;
            }
            embedded += written;
        }
        log.info("Retrieval: {} of {} adverts indexed with '{}'", embedded, due.size(), model);
        return new RetrievalReport(due.size(), embedded, requests, model);
    }

    /** One request, or -1 when the answer says this configuration cannot be used at all. */
    private int embed(EmbeddingModel embeddings, String model, List<Pending> batch) {
        List<float[]> vectors;
        try {
            vectors = embeddings.call(new EmbeddingRequest(batch.stream().map(Pending::text).toList(), null))
                    .getResults()
                    .stream()
                    .map(result -> result.getOutput())
                    .toList();
        } catch (RuntimeException e) {
            log.warn("Indexing {} adverts failed: {}", batch.size(), e.getMessage());
            return 0;
        }
        if (vectors.size() != batch.size()) {
            log.warn("Asked for {} embeddings and got {}; the batch is skipped", batch.size(), vectors.size());
            return 0;
        }

        int written = 0;
        for (int index = 0; index < batch.size(); index++) {
            float[] vector = vectors.get(index);
            if (vector.length < Vectors.DIMENSIONS) {
                log.warn(
                        "Model '{}' returns {}-dimensional vectors and offer.retrieval_embedding holds {}."
                                + " Configure a model of at least {} dimensions in llm.models.embedding,"
                                + " or nothing is indexed.",
                        model,
                        vector.length,
                        Vectors.DIMENSIONS,
                        Vectors.DIMENSIONS);
                return -1;
            }
            if (vector.length > Vectors.DIMENSIONS && saidTruncating.compareAndSet(false, true)) {
                log.info(
                        "Model '{}' returns {} dimensions; the leading {} are stored, which is the widest"
                                + " vector pgvector will index.",
                        model,
                        vector.length,
                        Vectors.DIMENSIONS);
            }
            jdbc.sql(RECORD)
                    .param("vector", Vectors.literal(Vectors.narrowed(vector)))
                    .param("model", model)
                    .param("id", batch.get(index).id())
                    .update();
            written++;
        }
        return written;
    }

    /**
     * The due population, with its text already built.
     *
     * <p>Read with a {@code RowMapper} and {@code getString}, never {@code listOfRows()}: a
     * {@code jsonb} column arrives from the driver as a {@code PGobject}, and the cast that
     * looks like it should work is what once left every segmented advert without a package for
     * nine days with a green suite.
     */
    private List<Pending> due(String model) {
        return jdbc.sql(DUE)
                .param("model", model)
                .query((rs, row) -> new Pending(
                        rs.getLong("id"),
                        AdvertText.of(
                                rs.getString("title"),
                                rs.getString("location"),
                                rs.getString("content_blocks"),
                                rs.getString("full_text"))))
                .list();
    }

    private record Pending(long id, String text) {}
}
