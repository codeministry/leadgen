/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.dedupe;

import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The two strategies that compare adverts which are not identical.
 *
 * <p>Both ask the same question of the same column and differ in one number and in what
 * they write. Above the merge threshold an offer is attached to its primary the way the
 * exact pass attaches one; above the lower one it is only marked, because the honest answer
 * between "certainly the same project" and "certainly not" is a person.
 *
 * <p><b>The configured number is a similarity and the operator is a distance.</b> pgvector's
 * {@code <=>} is cosine <em>distance</em>, so a threshold of 0.97 is a limit of 0.03. Reading
 * one as the other does not fail: it merges everything or nothing, and both look like a
 * plausible day.
 */
@Slf4j
@Component
public class SimilarOffers {

    /**
     * The older of a pair is the primary, which is what makes this idempotent: the relation
     * is antisymmetric, so a second run assigns what the first did.
     *
     * <p>The candidate is the <em>nearest</em> neighbour inside the limit rather than any of
     * them, because a merge is a decision about one project and the nearest is the only
     * defensible reading of "the same one".
     *
     * <p><b>A CTE with a correlated subquery, not {@code UPDATE ... FROM LATERAL}.</b> The
     * table an UPDATE writes to is not part of its FROM list, so a LATERAL item cannot see
     * it: Postgres answers "invalid reference to FROM-clause entry for table a", which reads
     * like a typo in an alias that is plainly there.
     */
    private static final String MERGE =
            """
            WITH pair AS (
                SELECT a.id AS id,
                       (SELECT b.id
                          FROM offer b
                         WHERE b.id <> a.id
                           AND b.embedding IS NOT NULL
                           AND b.embedding_model = a.embedding_model
                           AND b.duplicate_of_id IS NULL
                           AND (b.ingested_at, b.id) < (a.ingested_at, a.id)
                           AND b.ingested_at >= now() - make_interval(days => :ttl)
                           AND (b.embedding <=> a.embedding) <= :limit
                         ORDER BY b.embedding <=> a.embedding, b.ingested_at, b.id
                         LIMIT 1) AS primary_id
                  FROM offer a
                 WHERE a.embedding IS NOT NULL
                   AND a.duplicate_of_id IS NULL
                   AND a.ingested_at >= now() - make_interval(days => :ttl)
            )
            UPDATE offer o
               SET duplicate_of_id = pair.primary_id
              FROM pair
             WHERE o.id = pair.id
               AND pair.primary_id IS NOT NULL
            """;

    /**
     * The same shape, one column over. A row that was merged is skipped: it has an answer
     * already, and a maybe beside a yes is noise.
     */
    private static final String FLAG =
            """
            WITH pair AS (
                SELECT a.id AS id,
                       (SELECT b.id
                          FROM offer b
                         WHERE b.id <> a.id
                           AND b.embedding IS NOT NULL
                           AND b.embedding_model = a.embedding_model
                           AND (b.ingested_at, b.id) < (a.ingested_at, a.id)
                           AND b.ingested_at >= now() - make_interval(days => :ttl)
                           AND (b.embedding <=> a.embedding) <= :limit
                         ORDER BY b.embedding <=> a.embedding, b.ingested_at, b.id
                         LIMIT 1) AS primary_id
                  FROM offer a
                 WHERE a.embedding IS NOT NULL
                   AND a.duplicate_of_id IS NULL
                   AND a.ingested_at >= now() - make_interval(days => :ttl)
            )
            UPDATE offer o
               SET possible_duplicate_of_id = pair.primary_id
              FROM pair
             WHERE o.id = pair.id
               AND pair.primary_id IS NOT NULL
               AND o.possible_duplicate_of_id IS DISTINCT FROM pair.primary_id
            """;

    /**
     * One statement can leave A attached to B while B is attached to C, because every
     * candidate is chosen against the state the statement started from. Similarity is not
     * transitive, so there is no equivalence class to compute the way the exact pass does —
     * the chain is shortened afterwards instead.
     */
    private static final String FLATTEN =
            """
            UPDATE offer a
               SET duplicate_of_id = b.duplicate_of_id
              FROM offer b
             WHERE a.duplicate_of_id = b.id
               AND b.duplicate_of_id IS NOT NULL
               AND b.duplicate_of_id <> a.id
            """;

    /**
     * How often the chain is shortened before giving up. A chain longer than this would mean
     * five adverts each similar to the next and not to the one before, which is a shape
     * worth a log line rather than a loop with no end.
     */
    private static final int FLATTEN_PASSES = 5;

    private final JdbcClient jdbc;

    SimilarOffers(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    /**
     * Attaches every offer that has a near enough older neighbour.
     *
     * @param similarity the configured cosine similarity, between 0 and 1.
     * @return how many offers were attached this run.
     */
    public int merge(int ttlDays, double similarity) {
        int moved = jdbc.sql(MERGE)
                .param("ttl", ttlDays)
                .param("limit", 1 - similarity)
                .update();
        if (moved > 0) {
            flatten();
        }
        return moved;
    }

    /**
     * Marks every offer that has a neighbour inside the lower threshold and was not merged.
     *
     * @return how many offers were marked or re-pointed this run.
     */
    public int flag(int ttlDays, double similarity) {
        return jdbc.sql(FLAG)
                .param("ttl", ttlDays)
                .param("limit", 1 - similarity)
                .update();
    }

    private void flatten() {
        for (int pass = 0; pass < FLATTEN_PASSES; pass++) {
            if (jdbc.sql(FLATTEN).update() == 0) {
                return;
            }
        }
        log.warn(
                "Similarity merging still had chains to shorten after {} passes;"
                        + " some offers point at a primary that is itself attached",
                FLATTEN_PASSES);
    }
}
