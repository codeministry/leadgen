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
import de.codeministry.leadgen.config.model.MatchingRules.Deduplication;
import de.codeministry.leadgen.config.model.MatchingRules.Deduplication.Strategy;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Collapses the listings of one project into one cluster.
 *
 * <p>This is not the upsert in {@link de.codeministry.leadgen.ingest.store.OfferStore}.
 * That one collapses a <em>listing</em> seen twice, which is what re-reading a newsletter
 * produces. This one collapses one <em>project</em> that several portals advertise at
 * once, which is what 14.0 % of the measured corpus is.
 *
 * <p><b>The fingerprint is the normalized title and nothing else</b>, and that is a
 * measurement rather than a preference. The configured field list names {@code city},
 * {@code start_date}, {@code duration_months} and {@code top_skills}; all four arrive
 * from enrichment, which runs after this stage, so at this point only the title exists.
 * Adding the one field that does exist, the stated location, was measured over the corpus
 * and is worse: it collapses 127 offers instead of 180, and the 53 it gives up are
 * overwhelmingly correct merges lost to the same ad writing its location as "Nürnberg" in
 * one portal and "Remote und Nürnberg" in the next. A location has to be parsed before it
 * can be compared, and parsing it is enrichment's job. Until then the fingerprint stays
 * one field.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeduplicationService {

    /**
     * The strategy that needs no model, and runs first for that reason: what it resolves
     * costs nothing to resolve, and the similarity pass then has less to ask about.
     */
    private static final String EXACT_FINGERPRINT = "exact_fingerprint";

    /**
     * The one that does. Both configured actions use it, at two thresholds.
     */
    private static final String EMBEDDING_COSINE = "embedding_cosine";

    private static final String MERGE = "merge";

    private static final String FLAG = "flag_possible_duplicate";

    /**
     * One statement, and idempotent by construction: the primary of a group is recomputed
     * from the group itself every run, so a second run assigns exactly what the first did
     * and a listing arriving later attaches to the primary that is already there instead
     * of starting a rival cluster.
     *
     * <p>The final predicate restricts the write to rows whose assignment actually
     * changes, which is what makes the returned count mean "moved" rather than "seen".
     */
    private static final String CLUSTER = """
        WITH ranked AS (
            SELECT id,
                   first_value(id) OVER (
                       PARTITION BY fingerprint
                       ORDER BY ingested_at, id
                   ) AS primary_id
            FROM offer
            WHERE fingerprint <> ''
              AND ingested_at >= now() - make_interval(days => :ttl)
        )
        UPDATE offer o
        SET duplicate_of_id = CASE WHEN r.primary_id = o.id THEN NULL ELSE r.primary_id END
        FROM ranked r
        WHERE o.id = r.id
          AND o.duplicate_of_id IS DISTINCT FROM
              (CASE WHEN r.primary_id = o.id THEN NULL ELSE r.primary_id END)
        """;

    private final ConfigRegistry config;
    private final OfferEmbedder embedder;
    private final SimilarOffers similar;
    private final JdbcClient jdbc;

    /**
     * Clusters every offer inside the configured window and returns how many are attached
     * to a primary afterwards.
     *
     * <p>The number returned is the standing total, not the rows this run moved. A second
     * run moves nothing, and reporting zero there would read as "deduplication stopped
     * working" rather than "there was nothing left to do".
     *
     * <p><b>No transaction around the whole pass any more</b>, and that is the price of the
     * similarity strategies: they need vectors, computing a vector is an HTTP call, and a
     * transaction held open across a few hundred of them is a transaction held open for
     * minutes. Every statement here is atomic on its own and the pass is idempotent by
     * construction, so a run that dies halfway is repaired by the next one rather than by a
     * rollback — which is what the exact pass already relied on.
     */
    public int run() {
        Deduplication rules = config.snapshot().rules().deduplication();
        warnAboutUnsupported(rules.strategies());

        if (!mergesOnExactFingerprint(rules.strategies())) {
            log.warn("No 'exact_fingerprint' strategy with action 'merge' is configured; nothing is clustered");
            return attached(rules.ttlDays());
        }

        int moved = jdbc.sql(CLUSTER).param("ttl", rules.ttlDays()).update();
        moved += similar(rules);
        int attached = attached(rules.ttlDays());
        log.info(
                "Deduplication: {} offers attached to a primary within {} days, {} moved this run",
                attached,
                rules.ttlDays(),
                moved);
        return attached;
    }

    /**
     * The similarity half, in the order the configuration lists it: embed what has no
     * vector, merge what is near enough to act on, mark what is only close.
     *
     * @return how many offers the merging strategy moved.
     */
    private int similar(Deduplication rules) {
        Double mergeAt = threshold(rules, MERGE);
        Double flagAt = threshold(rules, FLAG);
        if (mergeAt == null && flagAt == null) {
            return 0;
        }
        if (embedder.model() == null) {
            // Not a warning: no embedding model is the shipped state, and the strategies are
            // in the shipped file so that configuring one is a line rather than a deploy.
            log.info("llm.models.embedding names no model; only 'exact_fingerprint' ran");
            return 0;
        }
        embedder.embed(rules.ttlDays());

        int moved = 0;
        if (mergeAt != null) {
            moved = similar.merge(rules.ttlDays(), mergeAt);
            log.info("Deduplication: {} offers merged at a cosine similarity of {}", moved, mergeAt);
        }
        if (flagAt != null) {
            int flagged = similar.flag(rules.ttlDays(), flagAt);
            log.info("Deduplication: {} offers marked as possible duplicates at {}", flagged, flagAt);
        }
        return moved;
    }

    /**
     * The similarity a strategy asks for, or nothing when it asks for none this can use.
     *
     * <p>A threshold outside {@code (0, 1]} is refused by name rather than clamped: cosine
     * similarity has that range, and a 92 meant as a percentage would otherwise merge the
     * entire window into one offer.
     */
    private Double threshold(Deduplication rules, String action) {
        if (rules.strategies() == null) {
            return null;
        }
        return rules.strategies().stream()
                .filter(s -> EMBEDDING_COSINE.equals(s.type()) && action.equals(s.action()))
                .map(Strategy::threshold)
                .filter(value -> {
                    if (value == null || value <= 0 || value > 1) {
                        log.warn(
                                "Strategy '{}' with action '{}' has threshold {}, which is not a cosine"
                                        + " similarity between 0 and 1; it is skipped",
                                EMBEDDING_COSINE,
                                action,
                                value);
                        return false;
                    }
                    return true;
                })
                .findFirst()
                .orElse(null);
    }

    private int attached(int ttlDays) {
        return jdbc.sql("""
            SELECT count(*) FROM offer
            WHERE duplicate_of_id IS NOT NULL
              AND ingested_at >= now() - make_interval(days => :ttl)
            """).param("ttl", ttlDays).query(Integer.class).single();
    }

    private boolean mergesOnExactFingerprint(List<Strategy> strategies) {
        return strategies != null
                && strategies.stream().anyMatch(s -> EXACT_FINGERPRINT.equals(s.type()) && MERGE.equals(s.action()));
    }

    /**
     * Loud but not fatal, for the reason it always was: failing here would break a shipped
     * default, and running silently would leave the operator believing a pass happened.
     * What is unsupported is now a shorter list than it was — both strategy types the
     * shipped file names are implemented, so this only fires for a type nobody wrote.
     */
    private void warnAboutUnsupported(List<Strategy> strategies) {
        if (strategies == null) {
            return;
        }
        strategies.stream()
                .filter(s -> !EXACT_FINGERPRINT.equals(s.type()) && !EMBEDDING_COSINE.equals(s.type()))
                .forEach(s -> log.warn(
                        "Deduplication strategy '{}' is configured but not implemented; it is skipped", s.type()));
    }
}
