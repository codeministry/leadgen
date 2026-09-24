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
 * The slim read the dashboard uses instead of {@link AnalyticsView}.
 *
 * <p>Three groups and nothing else: a fourteen-day intake trend, the archive's score bands,
 * and whether the last run finished cleanly. {@link AnalyticsQueryService#analytics()} answers
 * a bigger question — the whole analytics screen, ninety days of published-date axes, portals,
 * tags, response times — and paying for all of that to draw one dashboard card is the reason
 * this exists beside it rather than instead of it.
 */
public record AnalyticsSummary(List<IntakeDay> intake, ScoreBands scoreBands, RunHealth lastRun) {

    /**
     * One calendar day of the fourteen-day window, oldest first.
     *
     * @param extracted   primaries ingested this day — {@code duplicate_of_id IS NULL}, the
     *                    same rule {@link IntakeSeries.Day#primaries()} follows, for the same reason: a
     *                    project arriving through a second portal is not a second project.
     * @param shortlisted the same primaries whose {@code score_band} is {@code SHORTLISTED} on
     *                    this day. Rewritten by every scoring pass like every band count in this package —
     *                    the height of the bar is history, its shortlisted share is today's opinion about it.
     */
    public record IntakeDay(LocalDate day, int extracted, int shortlisted) {}

    /**
     * The archive's four score bands, over everything the scorer has ever judged.
     *
     * <p>{@code unscored} folds together two states {@link ScoreDistribution#unscored()} keeps
     * apart: the literal band {@code 'UNSCORED'} the scorer writes when there is no judge, and
     * a {@code NULL} band, which means the offer never reached the scorer at all. The full
     * analytics screen keeps that distinction because it matters for reading the histogram; this
     * summary has room for four numbers and not five, so both count as "not yet a verdict".
     */
    public record ScoreBands(int shortlisted, int review, int discarded, int unscored) {}

    /**
     * What the last recorded run has to say about itself.
     *
     * @param finishedAt  when the last recorded run ended, {@code null} when none has ever
     *                    been recorded — the same distinction {@link LastRunQueryService#lastRun()} makes,
     *                    carried through rather than turned into an all-zero row.
     * @param failedStage the pipeline stage that failed on that run, or {@code null} when none
     *                    did. A run that is reported at all already finished — {@code COMPLETE} or
     *                    {@code AWAITING_BATCH} — so this is never "the run crashed"; it is a per-source
     *                    stage such as one mailbox's {@code INGEST} throwing while the rest of the pass
     *                    went on, recorded in {@code pipeline_stage} and not read back anywhere before now.
     * @param mismatches  how many of that run's sources announced a count the extraction did
     *                    not match — {@link LastRunSource#complete()} is {@code false}. The one check
     *                    nothing else can make: a selector that stops matching loses offers, and fewer
     *                    offers is indistinguishable from a quiet day on the market.
     */
    public record RunHealth(Instant finishedAt, String failedStage, int mismatches) {}
}
