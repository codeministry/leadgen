/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.analytics;

import java.util.List;

/**
 * How the scores are spread, and where the two thresholds cut it.
 *
 * @param unscored its own number and its own bar, never a bucket. Unscored is not zero: it
 *     is an offer the deterministic reasons were written for and the total withheld,
 *     because a total from five of nine weights is not comparable to one from all nine.
 * @param shortlistAt read from the live configuration rather than restated. The browser's
 *     score ring carries 70 and 50 as input defaults, which is a second copy of a
 *     configured number waiting to drift away from the file that decides it.
 */
public record ScoreDistribution(int bucketSize, List<Bucket> buckets, int unscored, int shortlistAt, int reviewAt) {

    /** @param floor the inclusive lower bound; the top bucket also holds an exact 100. */
    public record Bucket(int floor, int count) {}
}
