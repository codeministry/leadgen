/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.analytics;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * The read behind {@code GET /api/v1/analytics/summary} — three numbers, not the whole screen.
 *
 * <p>Separate from {@link AnalyticsQueryService} for the reason that one is separate from
 * {@code OfferQueryService}: it answers a different, smaller question. The dashboard wants
 * "is the pipeline healthy and what came in lately", not ninety days of published-date axes,
 * portals, tags and response-time percentiles — paying for that to draw one card is the whole
 * reason this class exists beside it rather than reusing it.
 */
@Service
public class AnalyticsSummaryQueryService {

    /**
     * Always fourteen rows, zero-filled, regardless of what the archive holds — unlike
     * {@code AnalyticsQueryService.INTAKE_BY_INGESTED}, whose span is bounded by the data
     * itself. The dashboard card wants a fixed window, not one that shrinks to nothing on a
     * fresh clone or stretches back to the first offer ever ingested.
     *
     * <p>The day boundary is bound as {@code :zone}, exactly as every timestamp bucket in
     * {@code AnalyticsQueryService} is: {@code date_trunc}/{@code ::date} over a
     * {@code timestamptz} uses whatever timezone the connection happens to have, and two
     * readers in two zones would bucket "today" differently. {@code now()} is evaluated once,
     * inside the {@code today} CTE, so the fourteen days and the row filter agree with each
     * other even though the query takes more than an instant to run.
     *
     * <p>{@code extracted} counts primaries only — {@code duplicate_of_id IS NULL} — the same
     * rule {@code INTAKE_BY_INGESTED} counts by, for the same reason: a project reaching the
     * pipeline through a second portal is not a second project.
     */
    private static final String INTAKE = """
        WITH today AS (SELECT (now() AT TIME ZONE :zone)::date AS today),
        days AS (
            SELECT generate_series(t.today - INTERVAL '13 days', t.today, INTERVAL '1 day')::date AS day
            FROM today t
        ),
        counted AS (
            SELECT (o.ingested_at AT TIME ZONE :zone)::date AS day,
                   count(*) FILTER (WHERE o.duplicate_of_id IS NULL)                                  AS extracted,
                   count(*) FILTER (WHERE o.duplicate_of_id IS NULL AND o.score_band = 'SHORTLISTED') AS shortlisted
            FROM offer o, today t
            WHERE (o.ingested_at AT TIME ZONE :zone)::date >= t.today - INTERVAL '13 days'
            GROUP BY 1
        )
        SELECT d.day,
               coalesce(c.extracted, 0)   AS extracted,
               coalesce(c.shortlisted, 0) AS shortlisted
        FROM days d LEFT JOIN counted c USING (day)
        ORDER BY d.day
        """;

    /**
     * Over the whole archive, not clamped to a window — the same choice
     * {@code AnalyticsQueryService.SCORES}/{@code UNSCORED} make for the full score
     * distribution, and this mirrors it rather than inventing a second rule: neither of those
     * queries filters {@code archived_at}, so an offer does not leave its band behind by being
     * archived. That is deliberately not the funnel's choice — {@code OfferQueryService.funnel()}
     * excludes the archive because it answers "what is the working list doing", where this
     * answers "what has the scorer ever decided".
     *
     * <p>{@code score_band} is {@code NULL} for an offer that never reached the scorer and the
     * literal {@code 'UNSCORED'} for one the scorer looked at without a judge configured;
     * {@link AnalyticsSummary.ScoreBands} folds both into {@code unscored} because this summary
     * has four buckets and not five. {@link ScoreDistribution#unscored()} keeps them apart for
     * the full screen.
     */
    private static final String SCORE_BANDS = """
        SELECT coalesce(score_band, 'UNSCORED') AS band, count(*) AS offers
        FROM offer
        WHERE duplicate_of_id IS NULL
        GROUP BY 1
        """;

    /**
     * The one thing {@link LastRunQueryService} does not read back: which stage, if any,
     * failed on the run it reports.
     *
     * <p>{@code pipeline_stage} carries {@code OK}/{@code FAILED} per stage — written by
     * {@code PipelineRunRecorder.record} from the {@code StageLog} that timed the run — but
     * nothing on the read side has ever queried it before this endpoint. It cannot be read
     * through {@link LastRunQueryService} as it stands, because {@link LastRunView} carries no
     * run id to join on; rather than re-deriving "which run is last" a second time — the
     * ordering {@code LastRunQueryService.LAST_RUN} encodes, batching and tie-breaks included —
     * this joins on the {@code finished_at} that service already resolved. Two runs finishing
     * at the identical microsecond would defeat that join; nothing currently produces that.
     *
     * <p>In practice this can only ever name an {@code INGEST <source>} stage today: a stage
     * that fails elsewhere in the pipeline (`DEDUPE`, `FILTER`, `SCORE`, …) throws out of
     * {@code IngestService.runOnce} before {@code history.record(...)} runs at all, so that run
     * is never closed and never becomes "the last run" — it sits at {@code RUNNING} until the
     * next startup marks it {@code ABANDONED}, which {@link LastRunQueryService#lastRun()}
     * excludes. Only a per-source {@code INGEST} failure is caught inside the loop and lets the
     * rest of the pass, and the recorded row, complete.
     */
    private static final String FAILED_STAGE = """
        SELECT ps.stage
        FROM pipeline_stage ps
        JOIN pipeline_run pr ON pr.id = ps.run_id
        WHERE pr.finished_at = :finishedAt AND ps.status = 'FAILED'
        ORDER BY ps.position
        LIMIT 1
        """;

    private final JdbcClient jdbc;
    private final LastRunQueryService lastRun;

    AnalyticsSummaryQueryService(DataSource dataSource, LastRunQueryService lastRun) {
        this.jdbc = JdbcClient.create(dataSource);
        this.lastRun = lastRun;
    }

    public AnalyticsSummary summary() {
        String zone = ZoneId.systemDefault().getId();
        return new AnalyticsSummary(intake(zone), scoreBands(), runHealth());
    }

    private List<AnalyticsSummary.IntakeDay> intake(String zone) {
        return jdbc.sql(INTAKE)
                .param("zone", zone)
                .query((rs, index) -> new AnalyticsSummary.IntakeDay(
                        rs.getObject("day", LocalDate.class), rs.getInt("extracted"), rs.getInt("shortlisted")))
                .list();
    }

    private AnalyticsSummary.ScoreBands scoreBands() {
        Map<String, Integer> counted = new LinkedHashMap<>();
        jdbc.sql(SCORE_BANDS)
                .query((rs, index) -> counted.put(rs.getString("band"), rs.getInt("offers")))
                .list();
        return new AnalyticsSummary.ScoreBands(
                counted.getOrDefault("SHORTLISTED", 0),
                counted.getOrDefault("REVIEW", 0),
                counted.getOrDefault("DISCARDED", 0),
                counted.getOrDefault("UNSCORED", 0));
    }

    /**
     * Delegates the run itself to {@link LastRunQueryService} rather than re-selecting
     * {@code pipeline_run} — the ordering and the batching tie-break belong to that class alone.
     * {@code mismatches} is counted from the sources it already returned; {@code failedStage}
     * is the one field it cannot answer today, so it is filled in here from {@link #FAILED_STAGE}.
     */
    private AnalyticsSummary.RunHealth runHealth() {
        return lastRun.lastRun()
                .map(run -> new AnalyticsSummary.RunHealth(
                        run.finishedAt(), failedStage(run.finishedAt()), mismatches(run)))
                .orElseGet(() -> new AnalyticsSummary.RunHealth(null, null, 0));
    }

    private String failedStage(Instant finishedAt) {
        return jdbc.sql(FAILED_STAGE)
                .param("finishedAt", Timestamp.from(finishedAt))
                .query(String.class)
                .optional()
                .orElse(null);
    }

    private static int mismatches(LastRunView run) {
        return (int) run.sources().stream().filter(source -> !source.complete()).count();
    }
}
