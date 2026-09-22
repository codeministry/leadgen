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
import java.util.LinkedHashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The relatedness filter as SQL: a bounded neighbourhood, and nothing about the order.
 *
 * <p>The vector SQL lives here rather than in {@code OfferQueryService} so that the read side
 * keeps one shape — every other filter there is a clause and a parameter, and this one is too.
 *
 * <h2>Why this is a filter and not a seventh sort</h2>
 *
 * <p>A relevance order would need {@code ShortlistSort.expression()} to become a function of the
 * request, a fourth {@code Key} kind to carry a distance as a long, a sentinel for the rows with
 * no vector, and a fifth cursor component holding the query — because a relevance cursor
 * replayed against different words is the failure {@code Cursor}'s own javadoc describes for a
 * mismatched sort: identical bytes, different meaning, silent. A filter needs none of it, since
 * it narrows the set without redefining the key.
 *
 * <p>It also needs no measured threshold, which is what lets the column ship before its bands
 * are known: {@code retrieval.neighbours} is a <b>count</b>. A floor would be a similarity, and
 * a similarity on this column would have to be measured before it could act.
 *
 * <h2>The sign</h2>
 *
 * <p>{@code <=>} is cosine <i>distance</i>: it grows as two things get less alike, so
 * {@code ORDER BY … LIMIT k} takes the nearest k. Every similarity in a configuration file
 * means the opposite, which is the trap {@code SimilarOffers} already carries a note about.
 */
@Component
public class SemanticFilter {

    /**
     * The neighbourhood, as a subquery rather than a join.
     *
     * <p>Keeping it inside {@code IN (…)} is what makes this composable with everything else in
     * {@code where()}: the outer query's shape, its ordering and its keyset page are untouched,
     * and the planner still gets to use the HNSW index for the inner one.
     *
     * <p>{@code retrieval_embedding_model} is compared, not merely checked for null. Two models
     * are two spaces, so a row embedded by a different one is not far away — it is not
     * comparable at all, and a cosine against it is a number rather than an error.
     */
    private static final String NEAREST =
            """
         AND o.id IN (SELECT s.id
                        FROM offer s
                       WHERE s.retrieval_embedding IS NOT NULL
                         AND s.retrieval_embedding_model = :relatedModel
                       ORDER BY s.retrieval_embedding <=> CAST(:relatedVector AS vector)
                       LIMIT :relatedLimit)
        """;

    /**
     * The same, around an offer that is already in the table. <b>No model call:</b> both vectors
     * are stored, so this is a pure database question. The anchor is in its own result because
     * its distance to itself is zero, which is what keeps it visible in the list the reader is
     * looking at when they ask.
     */
    private static final String NEAREST_TO_OFFER =
            """
         AND o.id IN (SELECT s.id
                        FROM offer s
                       WHERE s.retrieval_embedding IS NOT NULL
                         AND s.retrieval_embedding_model = :relatedModel
                       ORDER BY s.retrieval_embedding
                                <=> (SELECT a.retrieval_embedding FROM offer a WHERE a.id = :relatedAnchor)
                       LIMIT :relatedLimit)
        """;

    private static final int DEFAULT_NEIGHBOURS = 200;

    private final ConfigRegistry config;
    private final QueryEmbedder queries;
    private final JdbcClient jdbc;

    SemanticFilter(ConfigRegistry config, QueryEmbedder queries, DataSource dataSource) {
        this.config = config;
        this.queries = queries;
        this.jdbc = JdbcClient.create(dataSource);
    }

    /**
     * Whether this installation can answer a relatedness question at all.
     *
     * <p>The whole capability flag. False is the state of a fresh clone, and the browser is told
     * so that it can leave the control out entirely rather than offer one the server ignores.
     */
    public boolean available() {
        PipelineConfig application = config.snapshot().application();
        PipelineConfig.Retrieval retrieval = application.retrieval();
        if (retrieval == null || !retrieval.enabled()) {
            return false;
        }
        return model(application) != null;
    }

    /**
     * How much of the working list can be reached by this filter, or null when it does not
     * exist here.
     *
     * <p>Two counts and not a percentage, because the sentence the browser builds from them
     * names offers. It is the answer to the question a short result raises in the first weeks:
     * the market was not quiet, most adverts simply have no vector yet. It stops being worth
     * printing when the two numbers meet, which the browser decides.
     */
    public RelatedCoverage coverage() {
        if (!available()) {
            return null;
        }
        String model = model(config.snapshot().application());
        return jdbc.sql(
                        """
                        SELECT count(*) FILTER (WHERE retrieval_embedding IS NOT NULL
                                                  AND retrieval_embedding_model = :model) AS readable,
                               count(*) AS total
                          FROM offer
                         WHERE status = 'PASSED' AND duplicate_of_id IS NULL AND archived_at IS NULL
                        """)
                .param("model", model)
                .query((rs, row) -> new RelatedCoverage(rs.getInt("readable"), rs.getInt("total")))
                .single();
    }

    /**
     * The title of the offer a {@code similar=} filter is anchored on, so the browser can name
     * it without a second request. Null when that offer is gone.
     */
    public String titleOf(long offer) {
        return jdbc.sql("SELECT title FROM offer WHERE id = :id")
                .param("id", offer)
                .query(String.class)
                .optional()
                .orElse(null);
    }

    /**
     * The clause and its parameters, or null when nothing was asked for.
     *
     * @throws RetrievalUnavailable when a relatedness filter was asked for and cannot be
     *     answered. <b>Never a silent widening:</b> ignoring the parameter would return the
     *     unfiltered list under a heading that says it was narrowed, and the count beside it
     *     would be true about a set the reader did not ask for. That is the one failure this
     *     whole read path is arranged to avoid, and a shared link is how it arrives.
     */
    public Narrowing narrow(String semantic, Long similarTo) {
        if (semantic == null && similarTo == null) {
            return null;
        }
        PipelineConfig application = config.snapshot().application();
        PipelineConfig.Retrieval retrieval = application.retrieval();
        if (retrieval == null || !retrieval.enabled()) {
            throw new RetrievalUnavailable(
                    "this installation does not search by meaning: the retrieval index is switched off");
        }
        String model = model(application);
        if (model == null) {
            throw new RetrievalUnavailable(
                    "this installation does not search by meaning: no embedding model is configured");
        }

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("relatedModel", model);
        params.put("relatedLimit", neighbours(retrieval));

        if (similarTo != null) {
            // Checked before the clause is built, and this is not belt and braces. A scalar
            // subselect for an offer with no vector yields NULL, `<=>` against NULL is NULL for
            // every row, and `ORDER BY NULL ... LIMIT k` then returns **k arbitrary offers** --
            // presented to the reader as the ones related to theirs. An empty list would be the
            // honest version of that answer; a sentence is better still, because the reader
            // learns that this advert has not been indexed rather than that nothing is like it.
            if (!indexed(similarTo, model)) {
                throw new RetrievalUnavailable(
                        "this offer has not been read for meaning yet, so nothing can be found near it");
            }
            params.put("relatedAnchor", similarTo);
            return new Narrowing(NEAREST_TO_OFFER, params);
        }
        String vector = queries.vectorFor(semantic, model)
                .orElseThrow(() -> new RetrievalUnavailable(
                        "the search phrase could not be read just now; the day's model budget may be spent"));
        params.put("relatedVector", vector);
        return new Narrowing(NEAREST, params);
    }

    /** Whether this offer has a vector from the model now configured. */
    private boolean indexed(long offer, String model) {
        return Boolean.TRUE.equals(jdbc.sql(
                        """
                        SELECT retrieval_embedding IS NOT NULL
                               AND retrieval_embedding_model = :model
                          FROM offer WHERE id = :id
                        """)
                .param("id", offer)
                .param("model", model)
                .query(Boolean.class)
                .optional()
                .orElse(false));
    }

    private static int neighbours(PipelineConfig.Retrieval retrieval) {
        Integer configured = retrieval.neighbours();
        return configured == null || configured < 1 ? DEFAULT_NEIGHBOURS : configured;
    }

    private static String model(PipelineConfig application) {
        PipelineConfig.Llm llm = application.llm();
        if (llm == null || llm.models() == null) {
            return null;
        }
        String model = llm.models().embedding();
        return model == null || model.isBlank() ? null : model;
    }

    /** A clause to append to the filters, and the values it names. */
    public record Narrowing(String sql, Map<String, Object> params) {}

    /**
     * @param readable offers on the working list this filter can reach.
     * @param total    offers on the working list.
     */
    public record RelatedCoverage(int readable, int total) {}

    /**
     * Asked for something this installation cannot do. A 400 and not a 500: the request named a
     * capability that is not configured here, which is the caller's to fix or to stop asking for.
     */
    public static class RetrievalUnavailable extends RuntimeException {
        public RetrievalUnavailable(String message) {
            super(message);
        }
    }
}
