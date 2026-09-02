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
 * <p>It collects rather than writes. The row these reference does not exist until the run is
 * over: the history row is written last, deliberately, because a run that failed halfway must
 * not leave one claiming a clean pass.
 *
 * <p>Not thread-safe, and it does not need to be: a run is sequential, and that sequence is
 * the point.
 */
public final class StageLog {

    private final List<StageTiming> timings = new ArrayList<>();

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
