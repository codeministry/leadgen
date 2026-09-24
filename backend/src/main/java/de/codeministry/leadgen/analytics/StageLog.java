/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.analytics;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Times the stages of one run, so the run row can say where the time went.
 *
 * <p>This is what Spring Batch was considered for and is not needed for. The pipeline has no
 * chunk work and no restart requirement — every stage is already idempotent, and "restart"
 * is spelled *run it again* — so what was actually wanted was per-stage timing and status.
 * That is this class and one table, rather than a second framework, nine tables of foreign
 * DDL under Flyway's ownership, and the ceremony of defeating job-instance identity to keep
 * a run repeatable.
 *
 * <p>It collects rather than writes. The timings are written with the run row when the run
 * ends: by {@code PipelineRunRecorder.record} on success, and by {@code recordFailure} when a
 * stage threw. A run that failed halfway therefore leaves a row saying {@code FAILED} and where,
 * rather than one claiming a clean pass or no timings at all.
 *
 * <p>Not thread-safe, and it does not need to be: a run is sequential, and that sequence is
 * the point.
 */
public final class StageLog {

    /**
     * Told which stage is starting, so a run in flight can say where it has got to.
     *
     * <p>Separate from the collecting this class otherwise does, and that separation is the
     * point: the per-stage table is written after the work because the run it references must
     * be over before it can claim anything, while "the stage happening right now" is only
     * worth anything before that. One overwrites itself on the open run row; the other is the
     * record.
     */
    @FunctionalInterface
    public interface Marker {

        /**
         * @param position 1-based, counting the stages this run has entered.
         */
        void entering(int position, String stage);

        /**
         * For a run nobody is watching — the tests, and any caller that has no open row.
         */
        Marker NONE = (position, stage) -> {};
    }

    private final List<StageTiming> timings = new ArrayList<>();

    private final Marker marker;

    public StageLog() {
        this(Marker.NONE);
    }

    public StageLog(Marker marker) {
        this.marker = marker;
    }

    /**
     * Times one stage.
     *
     * <p>Only this overload, and no {@code Runnable} twin: an expression lambda calling a
     * method with a return value is both value- and void-compatible, so the two would be
     * ambiguous at exactly the call sites that matter. Every stage here produces something
     * the report carries anyway.
     */
    public <T> T time(String stage, Supplier<T> body) {
        Instant startedAt = Instant.now();
        // Before the work and outside the try, because it is not part of it: a marker that
        // threw would fail the stage it was only supposed to describe, and the whole point of
        // writing progress is that nobody is watching when it goes wrong.
        marker.entering(timings.size() + 1, stage);
        try {
            T result = body.get();
            timings.add(new StageTiming(timings.size(), stage, startedAt, Instant.now(), StageTiming.OK, null));
            return result;
        } catch (RuntimeException e) {
            // Recorded and rethrown. Swallowing it here would turn a broken stage into a run
            // that merely produced nothing, which is the failure this whole table exists to
            // make visible.
            timings.add(new StageTiming(
                    timings.size(), stage, startedAt, Instant.now(), StageTiming.FAILED, e.getMessage()));
            throw e;
        }
    }

    public List<StageTiming> timings() {
        return List.copyOf(timings);
    }
}
