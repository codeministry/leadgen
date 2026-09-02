/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.score;

/**
 * What the shortlist looks like, and what this pass had to do to get there.
 *
 * <p><b>Only `scored` counts this run.</b> The rest are standing totals over everything
 * that has passed the filter, for the same reason {@code IngestReport.merged} is one: a
 * run judges only what is stale, so a second run legitimately judges nothing, and
 * per-run counts would then report an empty shortlist rather than an idle pass.
 *
 * @param considered offers on the shortlist's own terms: passed the filter, not a duplicate.
 * @param scored offers this pass sent to a judge. Zero is the normal case for a run that
 *     found nothing new, and also what a run with no judge configured reports.
 * @param unscored offers standing without a number, because no judge was configured when
 *     they were written. They still carry their deterministic reasons; see
 *     {@link Score#value()} for why the total is withheld rather than computed from half
 *     the weights.
 * @param shortlisted at or above `scoring.thresholds.auto_shortlist`.
 * @param review between `review` and `auto_shortlist`.
 * @param submitted offers handed to a batch instead of judged here. Their scores arrive
 *     minutes later through the collector, which then finishes the run by packaging and
 *     writing the digest. `scored` and `submitted` are never both non-zero: batching is on
 *     for a run or it is not.
 */
public record ScoringReport(int considered, int scored, int unscored, int shortlisted, int review, int submitted) {

    public static ScoringReport nothing() {
        return new ScoringReport(0, 0, 0, 0, 0, 0);
    }
}
