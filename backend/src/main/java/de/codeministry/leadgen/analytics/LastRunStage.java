/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.analytics;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Duration;
import java.time.Instant;

/**
 * One timed stage of the run that is being read back, as {@code pipeline_stage} remembers it.
 *
 * <p>The same six values {@link StageTiming} carries while a run collects them, as the API
 * hands them over: where the time went, stage by stage, which is the question the counts on
 * the run row cannot answer. Mirrored by {@code core/model/last-run.ts}.
 *
 * @param position the order the stage ran in, from zero. The one thing the browser sorts by.
 * @param stage    the server's name for it, {@code DEDUPE} or {@code INGEST <source>}; English
 *                 on every screen, like a score reason.
 * @param status   {@code OK}, or {@code FAILED} with the reason in {@code note}. A FAILED
 *                 source under a run that completed is normal; a FAILED stage last under a run
 *                 whose own status is FAILED is where that run stopped.
 */
public record LastRunStage(int position, String stage, Instant startedAt, Instant endedAt, String status, String note) {

    /**
     * How long the stage took. Derived here rather than in the browser, and
     * {@code @JsonProperty} for the same reason as {@link LastRunSource#complete()}: Jackson
     * serialises a record from its components, and an accessor is not one.
     */
    @JsonProperty("millis")
    public long millis() {
        return Duration.between(startedAt, endedAt).toMillis();
    }
}
