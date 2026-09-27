/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.chat.tools;

import de.codeministry.leadgen.chat.ChatBudget;
import de.codeministry.leadgen.config.ConfigRegistry;
import de.codeministry.leadgen.retrieval.QueryEmbedder;
import de.codeministry.leadgen.retrieval.SemanticFilter;
import java.sql.Timestamp;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Search by meaning, over the working list and the archive, as a tool the chat model can call.
 *
 * <p><b>Why the archive is in, when the offer search leaves it out.</b> "Have we seen anything
 * like this before" is the question this tool exists for, and most of what was ever seen has
 * aged off the working list. Every hit says which side it is on ({@link OfferHit#archived()}),
 * so an answer never presents an archived advert as open.
 *
 * <p><b>What it never returns</b>: a knocked-out offer (only {@code PASSED}), an attached
 * duplicate (primaries only: the same project through a second portal is inside its primary,
 * not beside it), and an offer without a vector from the model now configured. The last is the
 * pgvector trap: {@code <=>} against a NULL vector is NULL, and {@code ORDER BY NULL LIMIT k}
 * hands back k arbitrary rows presented as the nearest. A vector from another embedding model is
 * left out for the same reason, because two models are two spaces and a cosine across them is a
 * number rather than a distance. Nor the advert text, the content blocks, the agency or anything
 * read from a mail: {@link OfferHit} is the whole shape.
 *
 * <p>The question is embedded with the retrieval model the index was built with, through the
 * same {@link QueryEmbedder} the shortlist's {@code semantic=} filter uses, so it shares that
 * cache and asks the call budget before a request. Without retrieval, without an embedding model
 * or with the day's budget spent, it answers with the reason and no rows, never with rows chosen
 * some other way.
 *
 * <p>Read-only: one SELECT.
 */
@Component
@RequiredArgsConstructor
public class SemanticSearchTool {

    /**
     * Fifteen nearest, fewer than the offer search's page.
     *
     * <p>Nearness decays without an edge, so past the first dozen the rows are ever less related
     * and a model reading them starts finding patterns in noise. A question that needs more rows
     * is a filter question, and the offer search answers those with a full count.
     */
    public static final int PAGE = 15;

    /**
     * Primaries that passed, on either side of the archive, with a vector from the configured
     * model, nearest first; the id breaks a tie so two equal distances cannot swap between calls.
     */
    private static final String NEAREST = """
        SELECT o.id, o.title, s.name AS source_name, o.ingested_at, o.score_value,
               o.archived_at IS NOT NULL AS archived
        FROM offer o
        JOIN source s ON s.id = o.source_id
        WHERE o.status = 'PASSED' AND o.duplicate_of_id IS NULL
          AND o.retrieval_embedding IS NOT NULL
          AND o.retrieval_embedding_model = :model
        ORDER BY o.retrieval_embedding <=> CAST(:vector AS vector), o.id
        LIMIT :limit
        """;

    private final ConfigRegistry config;
    private final SemanticFilter semantic;
    private final QueryEmbedder queries;
    private final ChatBudget budget;
    private final JdbcClient jdbc;

    @Tool(
            name = "search_by_meaning",
            description = "Finds offers whose advert is closest in meaning to the given words, nearest first,"
                    + " across the working shortlist and the archive; each offer says whether it is"
                    + " archived. Use it for 'anything like ...' questions and search_offers for filters"
                    + " and counts. Returns at most "
                    + PAGE
                    + " offers. When 'unavailable' is set this installation cannot search by meaning:"
                    + " say so rather than guess. Cite an offer by its id.")
    public SemanticSearchResult searchByMeaning(
            @ToolParam(
                            description = "What the offers should be about, in a few words, e.g. 'event streaming"
                                    + " with Kafka'.")
                    String query) {
        if (query == null || query.isBlank()) {
            return SemanticSearchResult.absent("no words were given to search by");
        }
        if (!semantic.available()) {
            return SemanticSearchResult.absent("this installation does not search by meaning: the retrieval index"
                    + " is switched off or no embedding model is configured");
        }
        // Present once `available()` said so: it is the same key that decided it.
        String model = config.snapshot().application().llm().models().embedding();
        // The chat's own ceiling, never `llm.budget` (ISC-433): see QueryEmbedder.
        var vector = queries.vectorFor(query, model, budget::take);
        if (vector.isEmpty()) {
            return SemanticSearchResult.absent(
                    "the words could not be read for meaning just now; the day's model budget may be spent");
        }
        var offers = jdbc.sql(NEAREST)
                .param("model", model)
                .param("vector", vector.get())
                .param("limit", PAGE)
                .query((rs, row) -> new OfferHit(
                        rs.getLong("id"),
                        rs.getString("title"),
                        rs.getString("source_name"),
                        instant(rs.getTimestamp("ingested_at")),
                        rs.getObject("score_value", Integer.class),
                        rs.getBoolean("archived")))
                .list();
        return new SemanticSearchResult(null, offers);
    }

    private static java.time.Instant instant(Timestamp at) {
        return at == null ? null : at.toInstant();
    }
}
