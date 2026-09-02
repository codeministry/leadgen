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
import java.time.LocalDate;
import java.util.List;

/**
 * What the runs actually did, from `source_run` — the only append-only record of pipeline
 * volume in the schema, and therefore the one series on this screen that nothing recomputes.
 */
public record RunSeries(List<Day> days, List<Pass> passes, Instant historySince) {

    /**
     * @param announced null when no source stated a count. The comparison between what a
     *     document announced and what came out of it is the one check nothing else can make.
     */
    public record Day(LocalDate day, int runs, int documents, int extracted, int written, Integer announced) {}

    /**
     * One whole run, as that run reported itself.
     *
     * <p>This is the only thing on the screen that is neither a measurement of the current
     * archive nor a recomputation of it: it is what the pipeline said it had done, written
     * down before the next run destroyed the evidence. It cannot be backfilled, which is
     * why `historySince` exists — a chart that starts three weeks ago has to say that it
     * starts three weeks ago rather than that nothing happened before.
     */
    public record Pass(
            Instant finishedAt,
            String status,
            String rulesetVersion,
            String scoreModel,
            int extracted,
            int written,
            int filterConsidered,
            int filterPassed,
            int scored,
            int shortlisted,
            int packaged) {}
}
