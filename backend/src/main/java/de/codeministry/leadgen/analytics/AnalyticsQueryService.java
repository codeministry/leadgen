/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.analytics;

import de.codeministry.leadgen.application.ApplicationStatus;
import de.codeministry.leadgen.config.ConfigRegistry;
import de.codeministry.leadgen.config.model.MatchingRules;
import de.codeministry.leadgen.filter.FilterStage;
import de.codeministry.leadgen.offer.OfferQueryService;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * The read side of the archive as a shape over time, rather than as a list.
 *
 * <p>Read-only, and separate from `OfferQueryService` for the same reason that one is
 * separate from the stages that write: it owns a different question. That one answers
 * "what is on the shortlist"; this one answers "what is the market doing, and what are my
 * rules doing to it".
 *
 * <p><b>What is measured and what is recomputed are not the same thing, and this class
 * cannot make them the same.</b> `ingested_at` is written once and the upsert never touches
 * it, so an arrival day is a fact. `filter_stage` and the score columns are rewritten on
 * every run — the filter re-judges the whole archive by design, and the scorer overwrites
 * its columns and deletes its reasons. So a bar's height is history and its colours are
 * today's opinion about history. The records carry that distinction in their javadoc and
 * the screen carries it in two headings; there is no way to carry it in the numbers.
 */
@Service
public class AnalyticsQueryService {

    /**
     * Ninety days back on the published axis.
     *
     * <p>Two things at once. It stops one misparsed date from stretching the axis over
     * twenty thousand empty days — the format is source-configured and a bad one lands in
     * 1970. And it is what makes the chart readable: measured on the real archive, 232 of
     * 241 offers were published inside two months while nine older ones pulled the axis
     * across two years, so ninety-eight of a hundred and three weekly bars were empty.
     *
     * <p>What falls outside is counted and shown, never dropped quietly. A window that
     * hides offers without saying so is the same failure as a selector that stopped
     * matching.
     */
    private static final int PUBLISHED_WINDOW_DAYS = 90;

    private static final int SCORE_BUCKET_SIZE = 10;

    /**
     * Arrival volume per day.
     *
     * <p>Primaries only for everything that the funnel and the shortlist also count — a row
     * with `duplicate_of_id` set is the same project through a second portal, and counting
     * it would make the market look 12 % busier than it is. The duplicates are carried as
     * their own number instead, so the collapse is visible rather than absent.
     *
     * <p>The day boundary is bound as `:zone` rather than left to the session: `date_trunc`
     * over a `timestamptz` uses whatever timezone the connection happens to have, and two
     * readers in two zones would then bucket the same offer differently.
     *
     * <p>Empty days are filled here rather than in the browser. A line drawn straight across
     * a gap claims a quiet market on a day when nothing ran, and filling them in SQL keeps
     * the browser's week aggregation a plain sum with no gap logic in it.
     */
    private static final String INTAKE_BY_INGESTED =
            """
            WITH bounds AS (
                SELECT min((ingested_at AT TIME ZONE :zone)::date) AS lo,
                       max((ingested_at AT TIME ZONE :zone)::date) AS hi
                FROM offer
            ),
            days AS (
                SELECT generate_series(lo, hi, INTERVAL '1 day')::date AS day FROM bounds WHERE lo IS NOT NULL
            ),
            counted AS (
                SELECT (o.ingested_at AT TIME ZONE :zone)::date AS day,
                       count(*) FILTER (WHERE o.duplicate_of_id IS NULL)     AS primaries,
                       count(*) FILTER (WHERE o.duplicate_of_id IS NOT NULL) AS duplicates,
                       count(*) FILTER (WHERE o.duplicate_of_id IS NULL AND o.status = 'PASSED')            AS passed,
                       count(*) FILTER (WHERE o.duplicate_of_id IS NULL AND o.score_band = 'SHORTLISTED')   AS shortlisted,
                       count(*) FILTER (WHERE o.duplicate_of_id IS NULL AND o.score_band = 'REVIEW')        AS review,
                       count(*) FILTER (WHERE o.duplicate_of_id IS NULL AND o.score_band = 'DISCARDED')     AS discarded,
                       count(*) FILTER (WHERE o.duplicate_of_id IS NULL AND o.score_band = 'UNSCORED')      AS unscored
                FROM offer o
                GROUP BY 1
            )
            SELECT d.day,
                   coalesce(c.primaries, 0)   AS primaries,
                   coalesce(c.duplicates, 0)  AS duplicates,
                   coalesce(c.passed, 0)      AS passed,
                   coalesce(c.shortlisted, 0) AS shortlisted,
                   coalesce(c.review, 0)      AS review,
                   coalesce(c.discarded, 0)   AS discarded,
                   coalesce(c.unscored, 0)    AS unscored
            FROM days d LEFT JOIN counted c USING (day)
            ORDER BY d.day
            """;

    /**
     * The same shape on the date the advert states.
     *
     * <p>Clamped to the window, and the rows outside it are counted rather than dropped.
     * `published_on` is parsed with a source-configured `date_format`; one misparse puts a
     * row in 1970, and an unclamped `generate_series` then produces twenty thousand empty
     * days. A drifted format has to be visible, and so does an offer the window left out —
     * "nine offers outside the window" is how both become so.
     */
    private static final String INTAKE_BY_PUBLISHED =
            """
            WITH windowed AS (
                SELECT * FROM offer
                WHERE published_on BETWEEN (CURRENT_DATE - make_interval(days => :days))::date AND CURRENT_DATE
            ),
            bounds AS (SELECT min(published_on) AS lo, max(published_on) AS hi FROM windowed),
            days AS (
                SELECT generate_series(lo, hi, INTERVAL '1 day')::date AS day FROM bounds WHERE lo IS NOT NULL
            ),
            counted AS (
                SELECT o.published_on AS day,
                       count(*) FILTER (WHERE o.duplicate_of_id IS NULL)     AS primaries,
                       count(*) FILTER (WHERE o.duplicate_of_id IS NOT NULL) AS duplicates,
                       count(*) FILTER (WHERE o.duplicate_of_id IS NULL AND o.status = 'PASSED')          AS passed,
                       count(*) FILTER (WHERE o.duplicate_of_id IS NULL AND o.score_band = 'SHORTLISTED') AS shortlisted,
                       count(*) FILTER (WHERE o.duplicate_of_id IS NULL AND o.score_band = 'REVIEW')      AS review,
                       count(*) FILTER (WHERE o.duplicate_of_id IS NULL AND o.score_band = 'DISCARDED')   AS discarded,
                       count(*) FILTER (WHERE o.duplicate_of_id IS NULL AND o.score_band = 'UNSCORED')    AS unscored
                FROM windowed o
                GROUP BY 1
            )
            SELECT d.day,
                   coalesce(c.primaries, 0)   AS primaries,
                   coalesce(c.duplicates, 0)  AS duplicates,
                   coalesce(c.passed, 0)      AS passed,
                   coalesce(c.shortlisted, 0) AS shortlisted,
                   coalesce(c.review, 0)      AS review,
                   coalesce(c.discarded, 0)   AS discarded,
                   coalesce(c.unscored, 0)    AS unscored
            FROM days d LEFT JOIN counted c USING (day)
            ORDER BY d.day
            """;

    /**
     * The same shape on the date the document carrying the offer arrived.
     *
     * <p>The axis that measures the market's own tempo rather than the operator's. The
     * ingest axis also counts how often the tool was run — and a truncated database moves
     * every row to the moment it was refilled — while the published axis is only as good as
     * the date each advert states about itself. This one is the mail's own timestamp.
     *
     * <p>Not clamped: a mail is as old as it is, there is no format to misparse, and the
     * mailbox is not going to hand over a message from 1970.
     */
    private static final String INTAKE_BY_RECEIVED =
            """
            WITH dated AS (SELECT * FROM offer WHERE received_at IS NOT NULL),
            bounds AS (
                SELECT min((received_at AT TIME ZONE :zone)::date) AS lo,
                       max((received_at AT TIME ZONE :zone)::date) AS hi
                FROM dated
            ),
            days AS (
                SELECT generate_series(lo, hi, INTERVAL '1 day')::date AS day FROM bounds WHERE lo IS NOT NULL
            ),
            counted AS (
                SELECT (o.received_at AT TIME ZONE :zone)::date AS day,
                       count(*) FILTER (WHERE o.duplicate_of_id IS NULL)     AS primaries,
                       count(*) FILTER (WHERE o.duplicate_of_id IS NOT NULL) AS duplicates,
                       count(*) FILTER (WHERE o.duplicate_of_id IS NULL AND o.status = 'PASSED')          AS passed,
                       count(*) FILTER (WHERE o.duplicate_of_id IS NULL AND o.score_band = 'SHORTLISTED') AS shortlisted,
                       count(*) FILTER (WHERE o.duplicate_of_id IS NULL AND o.score_band = 'REVIEW')      AS review,
                       count(*) FILTER (WHERE o.duplicate_of_id IS NULL AND o.score_band = 'DISCARDED')   AS discarded,
                       count(*) FILTER (WHERE o.duplicate_of_id IS NULL AND o.score_band = 'UNSCORED')    AS unscored
                FROM dated o
                GROUP BY 1
            )
            SELECT d.day,
                   coalesce(c.primaries, 0)   AS primaries,
                   coalesce(c.duplicates, 0)  AS duplicates,
                   coalesce(c.passed, 0)      AS passed,
                   coalesce(c.shortlisted, 0) AS shortlisted,
                   coalesce(c.review, 0)      AS review,
                   coalesce(c.discarded, 0)   AS discarded,
                   coalesce(c.unscored, 0)    AS unscored
            FROM days d LEFT JOIN counted c USING (day)
            ORDER BY d.day
            """;

    /** What the published axis cannot show, stated rather than silently missing. */
    private static final String PUBLISHED_COVERAGE =
            """
            SELECT count(*) FILTER (WHERE published_on IS NULL) AS without_published,
                   count(*) FILTER (WHERE received_at IS NULL) AS without_received,
                   count(*) FILTER (
                       WHERE published_on IS NOT NULL
                         AND (published_on < (CURRENT_DATE - make_interval(days => :days))::date
                              OR published_on > CURRENT_DATE)
                   ) AS out_of_range
            FROM offer
            WHERE duplicate_of_id IS NULL
            """;

    /**
     * Portals by what they published and by what they brought in.
     *
     * <p>This is the one place the primaries-only rule is deliberately not applied to every
     * number, and the deviation is argued rather than made quietly. A portal that carried an
     * advert did carry it, even when another portal got there first and holds the primary;
     * counting primaries only would show a portal with zero listings that publishes every
     * day. So `listings` is what it published and `projects` is how many distinct projects
     * it brought in, and the survival rate is computed on `projects` because that is the set
     * the funnel and the shortlist count.
     */
    private static final String PORTALS =
            """
            SELECT o.portal,
                   count(*)                                                                           AS listings,
                   count(*) FILTER (WHERE o.duplicate_of_id IS NULL)                                  AS projects,
                   count(*) FILTER (WHERE o.duplicate_of_id IS NULL AND o.status = 'PASSED')          AS passed,
                   count(*) FILTER (WHERE o.duplicate_of_id IS NULL AND o.score_band = 'SHORTLISTED') AS shortlisted
            FROM offer o
            WHERE o.portal IS NOT NULL
            GROUP BY 1
            ORDER BY listings DESC, o.portal
            LIMIT 15
            """;

    /**
     * The tags the aggregator files its offers under — not skills read out of the advert.
     * Primaries only: one project filed under "Java" by three portals is one project's worth
     * of demand.
     */
    private static final String TAGS =
            """
            SELECT tag,
                   count(*)                                    AS projects,
                   count(*) FILTER (WHERE o.status = 'PASSED') AS passed
            FROM offer o, unnest(o.tags) AS tag
            WHERE o.duplicate_of_id IS NULL
            GROUP BY 1
            ORDER BY projects DESC, tag
            LIMIT 20
            """;

    /**
     * The location exactly as the advert stated it. Not normalised, and not grouped by any
     * rule invented here: deduplication already established that a location has to be parsed
     * before it can be compared, and half a normalisation inside a chart query is worse than
     * none.
     */
    private static final String LOCATIONS =
            """
            SELECT o.location,
                   count(*)                                    AS projects,
                   count(*) FILTER (WHERE o.status = 'PASSED') AS passed
            FROM offer o
            WHERE o.duplicate_of_id IS NULL AND o.location IS NOT NULL AND o.location <> ''
            GROUP BY 1
            ORDER BY projects DESC, o.location
            LIMIT 15
            """;

    /** The location question that is decided rather than guessed. */
    private static final String REACH =
            """
            SELECT count(*) FILTER (WHERE filter_stage = 'OUT_OF_REACH') AS out_of_reach,
                   count(*) FILTER (WHERE filter_stage = 'ABROAD')       AS abroad,
                   count(*) FILTER (WHERE filter_stage = 'REMOTE_SHARE') AS remote_share
            FROM offer
            WHERE duplicate_of_id IS NULL
            """;

    /**
     * Rejections per day and stage.
     *
     * <p>Days, not weeks, for the same reason the intake series sends days: the browser
     * buckets once and both charts then agree by construction. Sent weekly, this one
     * collapsed two days of mail into a single bar labelled with that week's Monday while
     * the chart above it showed two — the same archive, drawn two ways on one screen.
     *
     * <p>Primaries only on both sides, for the reason the funnel records: rejections are
     * written on duplicates too, and counting them against a primaries-only total once made
     * the rail claim minus forty-five survivors.
     */
    private static final String STAGE_MIX =
            """
            SELECT (o.ingested_at AT TIME ZONE :zone)::date AS day,
                   o.filter_stage,
                   count(*) AS removed
            FROM offer o
            WHERE o.filter_stage IS NOT NULL AND o.duplicate_of_id IS NULL
            GROUP BY 1, 2
            ORDER BY 1, 2
            """;

    /**
     * Ten buckets of ten.
     *
     * <p>`LEAST(..., 10)` is not cosmetic. `width_bucket(v, 0, 100, 10)` returns 11 for
     * exactly 100, so a perfect score would land in an eleventh bucket nothing draws and
     * would disappear without a trace.
     */
    private static final String SCORES =
            """
            SELECT LEAST(width_bucket(o.score_value, 0, 100, 10), 10) AS bucket, count(*) AS offers
            FROM offer o
            WHERE o.duplicate_of_id IS NULL AND o.score_value IS NOT NULL
            GROUP BY 1
            ORDER BY 1
            """;

    /**
     * Counted from the band, not from a null score. The scorer writes the literal
     * `'UNSCORED'` when there is no judge; a null band means the offer never reached the
     * scorer at all, which is a different thing and must not be added to this number.
     */
    private static final String UNSCORED =
            "SELECT count(*) FROM offer WHERE duplicate_of_id IS NULL AND score_band = 'UNSCORED'";

    private static final String APPLICATIONS_BY_STATUS =
            "SELECT status, count(*) AS applications FROM application GROUP BY 1";

    private static final String TRANSITIONS =
            """
            SELECT (e.recorded_at AT TIME ZONE :zone)::date AS day, e.to_status, count(*) AS moves
            FROM application_event e
            GROUP BY 1, 2
            ORDER BY 1, 2
            """;

    /**
     * How often an application is answered, and how long it takes.
     *
     * <p>Three judgements sit in this query and each of them is a decision rather than a
     * detail. <b>`REJECTED` counts as an answer</b> — a no is a reply — while `LOST` and
     * `EXPIRED` do not, because a project can end with nobody ever having written back and
     * counting those as replies would make the response rate flatter than it is. <b>A
     * negative gap is a back-dated `sent_on`</b>, which is a data-entry fact and not a
     * response time, so it is excluded from the medians and counted separately rather than
     * clamped to zero where it would quietly pull the median down. And
     * <b>`percentile_cont` ignores nulls</b>, which is what is wanted: an unanswered
     * application must count neither as zero days nor as infinity, so it counts as nothing
     * and `answered` says how many the median was computed over.
     */
    private static final String RESPONSE =
            """
            WITH first_reply AS (
                SELECT e.application_id,
                       min(e.recorded_at) FILTER (
                           WHERE e.to_status IN ('REPLIED', 'INTERVIEW', 'OFFER', 'WON', 'REJECTED')
                       ) AS replied_at
                FROM application_event e
                GROUP BY 1
            ),
            paired AS (
                SELECT a.id,
                       a.status,
                       (f.replied_at AT TIME ZONE :zone)::date - a.sent_on AS days
                FROM application a LEFT JOIN first_reply f ON f.application_id = a.id
                WHERE a.sent_on IS NOT NULL
            )
            SELECT count(*)                                                                   AS sent,
                   count(*) FILTER (WHERE days IS NOT NULL AND days >= 0)                     AS answered,
                   count(*) FILTER (WHERE days < 0)                                           AS backdated,
                   percentile_cont(0.5) WITHIN GROUP (ORDER BY days) FILTER (WHERE days >= 0) AS median_days,
                   percentile_cont(0.9) WITHIN GROUP (ORDER BY days) FILTER (WHERE days >= 0) AS p90_days,
                   count(*) FILTER (WHERE status = 'WON')                                     AS won,
                   count(*) FILTER (WHERE status = 'LOST')                                    AS lost,
                   count(*) FILTER (WHERE status = 'REJECTED')                                AS rejected
            FROM paired
            """;

    /** The one series on this screen that nothing recomputes. */
    private static final String RUNS =
            """
            WITH bounds AS (
                SELECT min((ran_at AT TIME ZONE :zone)::date) AS lo,
                       max((ran_at AT TIME ZONE :zone)::date) AS hi
                FROM source_run
            ),
            days AS (
                SELECT generate_series(lo, hi, INTERVAL '1 day')::date AS day FROM bounds WHERE lo IS NOT NULL
            ),
            counted AS (
                SELECT (r.ran_at AT TIME ZONE :zone)::date AS day,
                       count(*)         AS runs,
                       sum(r.documents) AS documents,
                       sum(r.extracted) AS extracted,
                       sum(r.written)   AS written,
                       sum(r.announced) AS announced
                FROM source_run r
                GROUP BY 1
            )
            SELECT d.day,
                   coalesce(c.runs, 0)      AS runs,
                   coalesce(c.documents, 0) AS documents,
                   coalesce(c.extracted, 0) AS extracted,
                   coalesce(c.written, 0)   AS written,
                   c.announced              AS announced
            FROM days d LEFT JOIN counted c USING (day)
            ORDER BY d.day
            """;

    /**
     * The runs themselves, newest last so a chart reads left to right.
     *
     * <p>Capped: the screen shows a trend, not an archive of runs, and thirty is more than
     * a month of daily use. The cap is here rather than in the browser because the payload
     * would otherwise grow without limit for a chart that cannot draw it.
     */
    private static final String PASSES =
            """
            SELECT finished_at, status, ruleset_version, score_model,
                   extracted, written, filter_considered, filter_passed,
                   scored, shortlisted, packaged
            FROM pipeline_run
            ORDER BY finished_at DESC
            LIMIT 30
            """;

    /** When this table started recording. Null means it never has, which is worth saying. */
    private static final String HISTORY_SINCE = "SELECT min(finished_at) FROM pipeline_run";

    private static final String SCALES =
            """
            SELECT ruleset_version, score_model, count(*) AS offers,
                   min(scored_at) AS first_scored_at, max(scored_at) AS last_scored_at
            FROM offer
            WHERE duplicate_of_id IS NULL AND score_value IS NOT NULL
            GROUP BY 1, 2
            ORDER BY offers DESC
            """;

    private final JdbcClient jdbc;
    private final OfferQueryService offers;
    private final ConfigRegistry config;

    AnalyticsQueryService(DataSource dataSource, OfferQueryService offers, ConfigRegistry config) {
        this.jdbc = JdbcClient.create(dataSource);
        this.offers = offers;
        this.config = config;
    }

    public AnalyticsView analytics() {
        String zone = ZoneId.systemDefault().getId();

        List<IntakeSeries.Day> byIngested = byIngestedAt(zone);
        List<IntakeSeries.Day> byPublished = byPublishedOn();
        List<IntakeSeries.Day> byReceived = byReceivedAt(zone);
        var coverage = jdbc.sql(PUBLISHED_COVERAGE)
                .param("days", PUBLISHED_WINDOW_DAYS)
                .query((rs, index) -> new int[] {
                    rs.getInt("without_published"), rs.getInt("out_of_range"), rs.getInt("without_received")
                })
                .single();

        return new AnalyticsView(
                zone,
                Instant.now(),
                byIngested.isEmpty() ? null : byIngested.getFirst().day(),
                byIngested.isEmpty() ? null : byIngested.getLast().day(),
                // Reused rather than recomputed: the funnel already knows the stages, their
                // labels and their order, and a second copy would be a second answer.
                offers.funnel(),
                new IntakeSeries(byIngested, byPublished, byReceived, coverage[0], coverage[1], coverage[2]),
                market(zone),
                scores(),
                applications(zone),
                runs(zone),
                jdbc.sql(SCALES).query(AnalyticsQueryService::scale).list());
    }

    /** The arrival axis: a timestamp, so it needs the zone the day is cut on. */
    private List<IntakeSeries.Day> byIngestedAt(String zone) {
        return jdbc.sql(INTAKE_BY_INGESTED)
                .param("zone", zone)
                .query(AnalyticsQueryService::intakeDay)
                .list();
    }

    /** The mail's own date. A timestamp, so it needs the zone; no clamp, a mail is as old as it is. */
    private List<IntakeSeries.Day> byReceivedAt(String zone) {
        return jdbc.sql(INTAKE_BY_RECEIVED)
                .param("zone", zone)
                .query(AnalyticsQueryService::intakeDay)
                .list();
    }

    /** The market axis: already a date, so no zone — but it needs the clamp. */
    private List<IntakeSeries.Day> byPublishedOn() {
        return jdbc.sql(INTAKE_BY_PUBLISHED)
                .param("days", PUBLISHED_WINDOW_DAYS)
                .query(AnalyticsQueryService::intakeDay)
                .list();
    }

    private static IntakeSeries.Day intakeDay(ResultSet rs, int index) throws SQLException {
        return new IntakeSeries.Day(
                rs.getObject("day", LocalDate.class),
                rs.getInt("primaries"),
                rs.getInt("duplicates"),
                rs.getInt("passed"),
                rs.getInt("shortlisted"),
                rs.getInt("review"),
                rs.getInt("discarded"),
                rs.getInt("unscored"));
    }

    private MarketView market(String zone) {
        var portals = jdbc.sql(PORTALS)
                .query((rs, index) -> new MarketView.Portal(
                        rs.getString("portal"),
                        rs.getInt("listings"),
                        rs.getInt("projects"),
                        rs.getInt("passed"),
                        rs.getInt("shortlisted")))
                .list();
        var tags = jdbc.sql(TAGS)
                .query((rs, index) ->
                        new MarketView.Tag(rs.getString("tag"), rs.getInt("projects"), rs.getInt("passed")))
                .list();
        var locations = jdbc.sql(LOCATIONS)
                .query((rs, index) ->
                        new MarketView.Location(rs.getString("location"), rs.getInt("projects"), rs.getInt("passed")))
                .list();
        var reach = jdbc.sql(REACH)
                .query((rs, index) ->
                        new MarketView.Reach(rs.getInt("out_of_reach"), rs.getInt("abroad"), rs.getInt("remote_share")))
                .single();
        var stageMix = jdbc.sql(STAGE_MIX)
                .param("zone", zone)
                .query((rs, index) -> new MarketView.StageDay(
                        rs.getObject("day", LocalDate.class),
                        // The same id the funnel uses, so a colour picked for a stage on one
                        // chart is the same colour on the other.
                        stageId(rs.getString("filter_stage")),
                        rs.getInt("removed")))
                .list();
        return new MarketView(portals, tags, locations, reach, stageMix);
    }

    private ScoreDistribution scores() {
        Map<Integer, Integer> counted = new LinkedHashMap<>();
        jdbc.sql(SCORES)
                .query((rs, index) -> counted.put(rs.getInt("bucket"), rs.getInt("offers")))
                .list();

        var buckets = new ArrayList<ScoreDistribution.Bucket>();
        for (int bucket = 1; bucket <= 100 / SCORE_BUCKET_SIZE; bucket++) {
            // Empty buckets are listed, for the same reason the funnel lists a stage that
            // removed nothing: a gap in the middle of a distribution is information.
            buckets.add(
                    new ScoreDistribution.Bucket((bucket - 1) * SCORE_BUCKET_SIZE, counted.getOrDefault(bucket, 0)));
        }

        MatchingRules.Scoring.Thresholds thresholds =
                config.snapshot().rules().scoring().thresholds();
        return new ScoreDistribution(
                SCORE_BUCKET_SIZE,
                buckets,
                jdbc.sql(UNSCORED).query(Integer.class).single(),
                thresholds.autoShortlist(),
                thresholds.review());
    }

    private ApplicationAnalytics applications(String zone) {
        Map<String, Integer> counted = new LinkedHashMap<>();
        jdbc.sql(APPLICATIONS_BY_STATUS)
                .query((rs, index) -> counted.put(rs.getString("status"), rs.getInt("applications")))
                .list();

        var byStatus = new ArrayList<ApplicationAnalytics.StatusCount>();
        for (ApplicationStatus status : ApplicationStatus.values()) {
            // Every state, in enum order, zeros included. The same rule as the funnel's, and
            // the same reason: a state nothing is in is a fact about the board.
            byStatus.add(new ApplicationAnalytics.StatusCount(status, counted.getOrDefault(status.name(), 0)));
        }

        var transitions = jdbc.sql(TRANSITIONS)
                .param("zone", zone)
                .query((rs, index) -> new ApplicationAnalytics.TransitionDay(
                        rs.getObject("day", LocalDate.class),
                        ApplicationStatus.valueOf(rs.getString("to_status")),
                        rs.getInt("moves")))
                .list();

        var response = jdbc.sql(RESPONSE)
                .param("zone", zone)
                .query((rs, index) -> new ApplicationAnalytics.ResponseMetrics(
                        rs.getInt("sent"),
                        rs.getInt("answered"),
                        rs.getInt("backdated"),
                        nullableDouble(rs, "median_days"),
                        nullableDouble(rs, "p90_days"),
                        rs.getInt("won"),
                        rs.getInt("lost"),
                        rs.getInt("rejected")))
                .single();

        return new ApplicationAnalytics(byStatus, transitions, response);
    }

    private RunSeries runs(String zone) {
        var passes = new java.util.ArrayList<>(jdbc.sql(PASSES)
                .query((rs, index) -> new RunSeries.Pass(
                        instant(rs, "finished_at"),
                        rs.getString("status"),
                        rs.getString("ruleset_version"),
                        rs.getString("score_model"),
                        rs.getInt("extracted"),
                        rs.getInt("written"),
                        rs.getInt("filter_considered"),
                        rs.getInt("filter_passed"),
                        rs.getInt("scored"),
                        rs.getInt("shortlisted"),
                        rs.getInt("packaged")))
                .list());
        // Newest first out of the database, because that is what LIMIT needs; reversed
        // here, because a chart reads left to right.
        java.util.Collections.reverse(passes);
        var since = jdbc.sql(HISTORY_SINCE)
                .query((rs, index) -> instant(rs, "min"))
                .optional()
                .orElse(null);
        return runs(zone, passes, since);
    }

    private RunSeries runs(String zone, List<RunSeries.Pass> passes, Instant since) {
        return new RunSeries(
                jdbc.sql(RUNS)
                        .param("zone", zone)
                        .query((rs, index) -> {
                            int announced = rs.getInt("announced");
                            return new RunSeries.Day(
                                    rs.getObject("day", LocalDate.class),
                                    rs.getInt("runs"),
                                    rs.getInt("documents"),
                                    rs.getInt("extracted"),
                                    rs.getInt("written"),
                                    // Null means no source stated a count, which is not the same as
                                    // a source that announced none.
                                    rs.wasNull() ? null : announced);
                        })
                        .list(),
                passes,
                since);
    }

    private static ScaleInUse scale(ResultSet rs, int index) throws SQLException {
        return new ScaleInUse(
                rs.getString("ruleset_version"),
                rs.getString("score_model"),
                rs.getInt("offers"),
                instant(rs, "first_scored_at"),
                instant(rs, "last_scored_at"));
    }

    /**
     * The Postgres driver refuses `getObject(column, Instant.class)` on a `timestamptz` and
     * throws naming the whole query rather than the column. The same helper sits in
     * `OfferQueryService` and `ApplicationService` for the same reason.
     */
    private static Instant instant(ResultSet rs, String column) throws SQLException {
        var timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static Double nullableDouble(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }

    /** The id the funnel publishes for a stage, so both charts name a stage identically. */
    private static String stageId(String stage) {
        return FilterStage.valueOf(stage).name().toLowerCase().replace('_', '-');
    }
}
