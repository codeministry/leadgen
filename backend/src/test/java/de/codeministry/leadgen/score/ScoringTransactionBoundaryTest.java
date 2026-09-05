/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.score;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Where the scoring stage's transaction begins and ends, pinned rather than remembered.
 *
 * <p>This is the same kind of test as {@code NothingIsSentTest}: the property is a
 * structural one, the cost of losing it is measured in hours, and nothing about a passing
 * run would reveal that it had been lost. An {@code @Transactional} put back on
 * {@code run} is a one-word change that looks tidier and is not.
 *
 * <p>What it costs: the stage holds a write lock on every offer it has already judged until
 * the last one is answered. With a local model that is hours, and every concurrent pass's
 * filter stage — which writes a verdict on all rows, with no {@code WHERE} — waits behind
 * it. Measured on the cluster: two filter updates blocked for thirteen minutes behind a
 * scoring transaction open for twenty, advancing one offer every 33 s, with a third run
 * stacked behind those. The second cost is that a run which dies halfway keeps nothing, and
 * the staleness guard then makes every offer due again at full price.
 */
class ScoringTransactionBoundaryTest {

    @Test
    void theScoringStageIsNotOneTransaction() throws Exception {
        var run = ScoringService.class.getDeclaredMethod("run", String.class);

        assertThat(run.isAnnotationPresent(Transactional.class))
                .as("ScoringService.run must not be @Transactional — see this class's javadoc")
                .isFalse();
    }

    @Test
    void oneOfferIsOneTransaction() throws Exception {
        // The three statements behind a single score do have to commit together: a score
        // whose reasons were deleted and not rewritten is worse than no score at all.
        var write = ScoreWriter.class.getDeclaredMethod("write", long.class, Score.class, int.class, int.class);

        assertThat(write.isAnnotationPresent(Transactional.class))
                .as("ScoreWriter.write must stay @Transactional, or a score can lose its reasons")
                .isTrue();
    }
}
