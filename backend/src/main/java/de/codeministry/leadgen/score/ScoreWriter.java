/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.score;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.util.List;

/**
 * The one place a score reaches the database.
 *
 * <p>Extracted because two paths now produce one: the synchronous run and the collector
 * that picks up a batch minutes later. A second copy of these two statements would drift
 * the first time a column is added, and the drift would be invisible — the batched half of
 * the shortlist would simply be missing something.
 */
@Component
class ScoreWriter {

    private final JdbcClient jdbc;

    ScoreWriter(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    /**
     * One offer, one transaction, and that boundary is load-bearing.
     *
     * <p>The three statements below have to commit together — a score whose reasons were
     * deleted and not rewritten is worse than no score. What must <em>not</em> happen is the
     * whole scoring stage sharing one transaction: it holds a write lock on every offer it
     * has touched until the last one is judged, and with a local model that is hours. Any
     * other run's filter stage writes a verdict on every row and therefore waits behind it.
     * Measured: two filter updates blocked for thirteen minutes behind a scoring
     * transaction that had been open for twenty, on a run advancing one offer every 33 s.
     *
     * <p>The second effect is that a run which dies halfway keeps the scores it had already
     * produced. Under one transaction it kept none, and the staleness guard then made every
     * one of them due again at full price.
     */
    @Transactional
    void write(long offerId, Score score, int autoShortlist, int review) {
        jdbc.sql(
                        """
                        UPDATE offer
                        SET score_value = ?, score_band = ?, score_model = ?, ruleset_version = ?, scored_at = now()
                        WHERE id = ?
                        """)
                .params(
                        score.value(),
                        score.band(autoShortlist, review),
                        score.model(),
                        score.rulesetVersion(),
                        offerId)
                .update();

        jdbc.sql("DELETE FROM offer_score_reason WHERE offer_id = ?")
                .param(offerId)
                .update();
        List<ScoreReason> reasons = score.reasons();
        for (int position = 0; position < reasons.size(); position++) {
            ScoreReason reason = reasons.get(position);
            jdbc.sql(
                            """
                            INSERT INTO offer_score_reason (offer_id, factor, label, points, position)
                            VALUES (?, ?, ?, ?, ?)
                            """)
                    .params(offerId, reason.factor(), reason.label(), reason.points(), position)
                    .update();
        }
    }
}
