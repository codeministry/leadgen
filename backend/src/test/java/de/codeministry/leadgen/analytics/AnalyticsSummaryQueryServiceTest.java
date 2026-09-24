/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import de.codeministry.leadgen.Databases;
import de.codeministry.leadgen.config.ConfigFixtures;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * What the dashboard's slim read answers, as opposed to what the full analytics screen does.
 *
 * <p>Every case here is a way the summary could look plausible and be wrong: a window that
 * shrinks on a quiet day instead of zero-filling, a duplicate counted as a second arrival, an
 * archived offer dropped from its own score band, and a run reported as healthy because
 * nothing ever looked at the one table that says otherwise.
 */
@SpringBootTest
@Testcontainers
class AnalyticsSummaryQueryServiceTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = Databases.postgres();

    /**
     * Pinned to the shipped defaults rather than to whatever {@code config/} this machine has.
     * Without it the run would be read against the developer's own directory through
     * {@code .env}, and the build turns red for a value nobody committed.
     */
    @DynamicPropertySource
    static void configuration(DynamicPropertyRegistry registry) {
        registry.add(
                "leadgen.config-dir", () -> ConfigFixtures.shippedDefaults().toString());
    }

    @Autowired
    private AnalyticsSummaryQueryService summary;

    @Autowired
    private JdbcTemplate jdbc;

    private long sourceId;
    private long otherSourceId;

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM offer");
        jdbc.update("DELETE FROM pipeline_run");
        jdbc.update("DELETE FROM source_run");
        jdbc.update("DELETE FROM source");
        sourceId = jdbc.queryForObject(
                "INSERT INTO source (name, kind) VALUES ('demo-newsletter', 'file') RETURNING id", Long.class);
        otherSourceId = jdbc.queryForObject(
                "INSERT INTO source (name, kind) VALUES ('manual-inbox', 'file') RETURNING id", Long.class);
    }

    @Test
    void fillsExactlyFourteenDaysZeroFilledWithTodayLast() {
        LocalDate today = LocalDate.now();
        // Outside the window: must neither appear nor stretch it, the same trap
        // AnalyticsQueryService's ninety-day clamp exists for.
        arrived(today.minusDays(20));
        // The oldest day the window actually covers.
        arrived(today.minusDays(13));
        arrived(today);

        var days = summary.summary().intake();

        assertThat(days).hasSize(14);
        assertThat(days.getFirst().day()).isEqualTo(today.minusDays(13));
        assertThat(days.getLast().day()).isEqualTo(today);
        assertThat(days.getFirst().extracted()).isEqualTo(1);
        assertThat(days.getLast().extracted()).isEqualTo(1);
        // The twelve days in between, where nothing arrived, read zero rather than being
        // absent from the series — a line drawn straight across a gap claims a quiet market
        // on a day nothing ran.
        assertThat(days.subList(1, 13))
                .allSatisfy(day -> assertThat(day.extracted()).isZero());
    }

    @Test
    void extractedCountsPrimariesOnlyAndNotDuplicates() {
        LocalDate today = LocalDate.now();
        long primary = arrived(today);
        long duplicate = arrived(today);
        jdbc.update("UPDATE offer SET duplicate_of_id = ? WHERE id = ?", primary, duplicate);

        var last = summary.summary().intake().getLast();

        assertThat(last.extracted()).isEqualTo(1);
    }

    @Test
    void shortlistedIsCountedPerDayAlongsideExtracted() {
        LocalDate today = LocalDate.now();
        long shortlisted = arrived(today);
        jdbc.update("UPDATE offer SET score_band = 'SHORTLISTED' WHERE id = ?", shortlisted);
        arrived(today);

        var last = summary.summary().intake().getLast();

        assertThat(last.extracted()).isEqualTo(2);
        assertThat(last.shortlisted()).isEqualTo(1);
    }

    @Test
    void scoreBandsAreCountedOverTheWholeArchiveIncludingArchivedOffers() {
        // Mirrors AnalyticsQueryService.SCORES/UNSCORED, which do not filter archived_at
        // either: this answers "what has the scorer ever decided", not "what the working
        // list is doing" — that second question is the funnel's, and it does exclude the
        // archive.
        LocalDate today = LocalDate.now();
        long shortlisted = arrived(today);
        jdbc.update("UPDATE offer SET score_band = 'SHORTLISTED', archived_at = now() WHERE id = ?", shortlisted);
        long review = arrived(today);
        jdbc.update("UPDATE offer SET score_band = 'REVIEW' WHERE id = ?", review);
        long discarded = arrived(today);
        jdbc.update("UPDATE offer SET score_band = 'DISCARDED' WHERE id = ?", discarded);
        long unscoredLiteral = arrived(today);
        jdbc.update("UPDATE offer SET score_band = 'UNSCORED' WHERE id = ?", unscoredLiteral);
        // Never reached the scorer at all: score_band stays NULL.
        arrived(today);

        var bands = summary.summary().scoreBands();

        assertThat(bands.shortlisted()).isEqualTo(1);
        assertThat(bands.review()).isEqualTo(1);
        assertThat(bands.discarded()).isEqualTo(1);
        // The literal 'UNSCORED' band and the never-scored NULL band both fold into this one
        // number — this summary has four buckets, not the five ScoreDistribution keeps apart.
        assertThat(bands.unscored()).isEqualTo(2);
    }

    @Test
    void scoreBandsExcludeDuplicates() {
        LocalDate today = LocalDate.now();
        long primary = arrived(today);
        jdbc.update("UPDATE offer SET score_band = 'SHORTLISTED' WHERE id = ?", primary);
        long duplicate = arrived(today);
        jdbc.update(
                "UPDATE offer SET score_band = 'SHORTLISTED', duplicate_of_id = ? WHERE id = ?", primary, duplicate);

        assertThat(summary.summary().scoreBands().shortlisted()).isEqualTo(1);
    }

    @Test
    void lastRunIsAllNullAndZeroWhenNothingHasEverRun() {
        var lastRun = summary.summary().lastRun();

        assertThat(lastRun.finishedAt()).isNull();
        assertThat(lastRun.failedStage()).isNull();
        assertThat(lastRun.mismatches()).isZero();
    }

    @Test
    void lastRunNamesTheStageThatFailed() {
        Instant startedAt = Instant.now().minus(5, ChronoUnit.MINUTES);
        Instant finishedAt = startedAt.plusSeconds(90);
        long runId = run(startedAt, finishedAt, "COMPLETE", "claude-haiku-4-5");
        stageTiming(
                runId,
                0,
                "INGEST demo-newsletter",
                startedAt,
                startedAt.plusSeconds(10),
                "FAILED",
                "mailbox unreachable");
        stageTiming(runId, 1, "DEDUPE", startedAt.plusSeconds(10), finishedAt, "OK", null);

        var lastRun = summary.summary().lastRun();

        assertThat(lastRun.finishedAt()).isCloseTo(finishedAt, within(1, ChronoUnit.SECONDS));
        assertThat(lastRun.failedStage()).isEqualTo("INGEST demo-newsletter");
    }

    @Test
    void lastRunNamesNoFailedStageWhenEveryStageFinishedOk() {
        Instant startedAt = Instant.now().minus(5, ChronoUnit.MINUTES);
        long runId = run(startedAt, startedAt.plusSeconds(60), "COMPLETE", "claude-haiku-4-5");
        stageTiming(runId, 0, "DEDUPE", startedAt, startedAt.plusSeconds(60), "OK", null);

        assertThat(summary.summary().lastRun().failedStage()).isNull();
    }

    @Test
    void lastRunCountsSourcesWhoseAnnouncedCountDoesNotMatchWhatWasExtracted() {
        Instant startedAt = Instant.now().minus(5, ChronoUnit.MINUTES);
        run(startedAt, startedAt.plusSeconds(60), "COMPLETE", "claude-haiku-4-5");
        // Announced 169, extracted 140: a selector that stopped matching, the one check
        // nothing else can make.
        sourceRun(sourceId, startedAt.plusSeconds(5), 5, 140, 140, 169);
        sourceRun(otherSourceId, startedAt.plusSeconds(6), 3, 42, 42, 42);

        assertThat(summary.summary().lastRun().mismatches()).isEqualTo(1);
    }

    @Test
    void lastRunCountsNoMismatchWhenNoSourceStatesACount() {
        Instant startedAt = Instant.now().minus(5, ChronoUnit.MINUTES);
        run(startedAt, startedAt.plusSeconds(60), "COMPLETE", "claude-haiku-4-5");
        // Null announced, not zero: this source states no count to check against, which is
        // most of them, and must not be counted as a mismatch.
        sourceRun(sourceId, startedAt.plusSeconds(5), 5, 140, 140, null);

        assertThat(summary.summary().lastRun().mismatches()).isZero();
    }

    private long arrived(LocalDate day) {
        return jdbc.queryForObject(
                """
                INSERT INTO offer (source_id, external_id, title, url, portal, fingerprint, status, ingested_at)
                VALUES (?, ?, 'Java Entwickler', 'https://example.invalid/x', 'portal-a', 'fp', 'PASSED', ?)
                RETURNING id
                """, Long.class, sourceId, "ext-" + System.nanoTime(), Timestamp.valueOf(day.atTime(9, 0)));
    }

    private long run(Instant startedAt, Instant finishedAt, String status, String scoreModel) {
        return jdbc.queryForObject(
                """
                INSERT INTO pipeline_run (
                    started_at, finished_at, ruleset_version, score_model, status,
                    documents, extracted, written, merged,
                    filter_considered, filter_passed,
                    enrich_considered, enriched, incomplete, from_cache, requests,
                    score_considered, scored, unscored, shortlisted, review, submitted,
                    packaged, digest_written)
                VALUES (?, ?, '1', ?, ?, 5, 169, 151, 18, 169, 73, 73, 0, 73, 0, 0, 67, 67, 0, 7, 13, 0, 7, true)
                RETURNING id
                """, Long.class, Timestamp.from(startedAt), Timestamp.from(finishedAt), scoreModel, status);
    }

    private void stageTiming(
            long runId, int position, String stage, Instant startedAt, Instant endedAt, String status, String note) {
        jdbc.update(
                "INSERT INTO pipeline_stage (run_id, position, stage, started_at, ended_at, status, note)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                runId,
                position,
                stage,
                Timestamp.from(startedAt),
                Timestamp.from(endedAt),
                status,
                note);
    }

    private void sourceRun(long source, Instant ranAt, int documents, int extracted, int written, Integer announced) {
        jdbc.update(
                "INSERT INTO source_run (source_id, ran_at, documents, extracted, written, announced)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                source,
                Timestamp.from(ranAt),
                documents,
                extracted,
                written,
                announced);
    }
}
