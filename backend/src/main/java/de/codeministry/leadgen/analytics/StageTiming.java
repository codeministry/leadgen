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

/**
 * What one stage of a run cost, and whether it finished.
 *
 * @param position the order it ran in. Kept explicitly rather than inferred from the
 *     timestamps: two stages that both take under a millisecond would otherwise sort
 *     arbitrarily, and the order is the one thing about this pipeline that is load-bearing.
 * @param status {@code OK}, or {@code FAILED} with the reason in {@code note}. A stage that
 *     threw still gets a row — "the run stopped here" is the most useful thing this can say.
 */
public record StageTiming(int position, String stage, Instant startedAt, Instant endedAt, String status, String note) {

    public static final String OK = "OK";
    public static final String FAILED = "FAILED";
}
