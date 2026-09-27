/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * What the run's own stopwatch has to guarantee.
 *
 * <p>Small, and deliberately so: this is the whole of what Spring Batch was considered for.
 * The pipeline has no chunk work and no restart requirement, so what was actually wanted was
 * per-stage timing and status — this class and one table, rather than nine tables of foreign
 * DDL under Flyway's ownership.
 */
class StageLogTest {

    @Test
    void keepsTheOrderTheStagesRanIn() {
        // Position rather than timestamp order: two stages that both finish inside a
        // millisecond would otherwise sort arbitrarily, and the order is the one thing about
        // this pipeline that is load-bearing.
        var log = new StageLog();
        log.time("DEDUPE", () -> 1);
        log.time("FILTER", () -> 2);
        log.time("SCORE", () -> 3);

        assertThat(log.timings())
                .extracting(StageTiming::position, StageTiming::stage, StageTiming::status)
                .containsExactly(
                        org.assertj.core.api.Assertions.tuple(0, "DEDUPE", StageTiming.OK),
                        org.assertj.core.api.Assertions.tuple(1, "FILTER", StageTiming.OK),
                        org.assertj.core.api.Assertions.tuple(2, "SCORE", StageTiming.OK));
    }

    @Test
    void recordsAFailedStageAndStillLetsItThrough() {
        // Swallowing here would turn a broken stage into a run that merely produced nothing,
        // which is exactly the failure this table exists to make visible. The row says where
        // the run stopped; the exception still ends it.
        var log = new StageLog();
        log.time("DEDUPE", () -> 1);

        assertThatThrownBy(() -> log.time("FILTER", () -> {
                    throw new IllegalStateException("the rules did not load");
                }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(log.timings()).hasSize(2);
        assertThat(log.timings().getLast().status()).isEqualTo(StageTiming.FAILED);
        assertThat(log.timings().getLast().note()).isEqualTo("the rules did not load");
    }

    @Test
    void carriesTheNoteOfAStageThatFinished() {
        // Read after the body, so a stage can say what it ran at once it has run. A stage
        // timed without one keeps the null a width-1 run has always written.
        var log = new StageLog();
        log.time("DEDUPE", () -> 1);
        log.time("CONTENT", () -> 2, result -> "width=4");

        assertThat(log.timings())
                .extracting(StageTiming::stage, StageTiming::status, StageTiming::note)
                .containsExactly(
                        org.assertj.core.api.Assertions.tuple("DEDUPE", StageTiming.OK, null),
                        org.assertj.core.api.Assertions.tuple("CONTENT", StageTiming.OK, "width=4"));
    }

    @Test
    void aFailedStageKeepsItsReasonRatherThanItsNote() {
        var log = new StageLog();

        assertThatThrownBy(() -> log.time(
                        "SCORE",
                        () -> {
                            throw new IllegalStateException("the model is gone");
                        },
                        result -> "width=4"))
                .isInstanceOf(IllegalStateException.class);

        assertThat(log.timings().getLast().status()).isEqualTo(StageTiming.FAILED);
        assertThat(log.timings().getLast().note()).isEqualTo("the model is gone");
    }

    @Test
    void aNoteThatThrowsLeavesTheStageOkWithoutANote() {
        // The body returned, so the stage succeeded; a note that cannot be worked out must not turn
        // that into a FAILED row, nor lose the timing.
        var log = new StageLog();

        Integer result = log.time("SCORE", () -> 7, r -> {
            throw new IllegalStateException("no width");
        });

        assertThat(result).isEqualTo(7);
        assertThat(log.timings()).singleElement().satisfies(timing -> {
            assertThat(timing.status()).isEqualTo(StageTiming.OK);
            assertThat(timing.note()).isNull();
        });
    }

    @Test
    void theNoteIsReadOffWhatTheStageReturned() {
        var log = new StageLog();

        log.time("FIELDS", () -> 4, width -> "width=" + width);

        assertThat(log.timings().getLast().note()).isEqualTo("width=4");
    }

    @Test
    void handsOutACopyRatherThanItsOwnList() {
        var log = new StageLog();
        log.time("DEDUPE", () -> 1);
        var taken = log.timings();
        log.time("FILTER", () -> 2);

        assertThat(taken).hasSize(1);
    }
}
