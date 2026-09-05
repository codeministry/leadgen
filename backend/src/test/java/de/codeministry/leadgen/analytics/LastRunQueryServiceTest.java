/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.analytics;

import de.codeministry.leadgen.config.ConfigFixtures;
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

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the dashboard reads when nobody has pressed the button in this browser.
 *
 * <p>Every case here is a way of reporting the wrong run while looking entirely normal
 * doing it: an empty state where a run exists, an older run outranking a newer one because
 * a batch finished late, and a previous run's source rows arriving inside this one's table.
 */
@SpringBootTest
@Testcontainers
class LastRunQueryServiceTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    /**
     * Pinned to the shipped defaults rather than to whatever `config/` this machine has.
     * Without it the run would be read against the developer's own directory through
     * `.env`, and the build turns red for a value nobody committed.
     */
    @DynamicPropertySource
    static void configuration(DynamicPropertyRegistry registry) {
        registry.add(
                "leadgen.config-dir", () -> ConfigFixtures.shippedDefaults().toString());
    }

    @Autowired
    private LastRunQueryService runs;

    @Autowired
    private JdbcTemplate jdbc;

    private long sourceId;
    private long otherSourceId;

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM pipeline_run_stage");
        jdbc.update("DELETE FROM pipeline_run");
        jdbc.update("DELETE FROM source_run");
        jdbc.update("DELETE FROM source");
        sourceId = jdbc.queryForObject(
                "INSERT INTO source (name, kind) VALUES ('demo-newsletter', 'file') RETURNING id", Long.class);
        otherSourceId = jdbc.queryForObject(
                "INSERT INTO source (name, kind) VALUES ('manual-inbox', 'file') RETURNING id", Long.class);
    }

    @Test
    void saysNothingRatherThanZeroWhenNothingHasEverRun() {
        // The distinction the endpoint's 204 exists for: a run with every count at zero is
        // a different fact from no run at all, and a caller must not have to tell them
        // apart by inspecting the numbers.
        assertThat(runs.lastRun()).isEmpty();
    }

    @Test
    void readsTheRunItsStagesAndItsSources() {
        Instant startedAt = Instant.now().minus(5, ChronoUnit.MINUTES);
        long id = run(startedAt, startedAt.plusSeconds(90), "COMPLETE", "claude-haiku-4-5");
        stage(id, "ABROAD", 13);
        stage(id, "ROLE_OR_STACK", 55);
        sourceRun(sourceId, startedAt.plusSeconds(5), 5, 169, 151, 169);
        sourceRun(otherSourceId, startedAt.plusSeconds(6), 0, 0, 0, null);

        var last = runs.lastRun().orElseThrow();

        assertThat(last.status()).isEqualTo("COMPLETE");
        assertThat(last.scoreModel()).isEqualTo("claude-haiku-4-5");
        assertThat(last.extracted()).isEqualTo(169);
        assertThat(last.written()).isEqualTo(151);
        assertThat(last.removed())
                .containsExactlyInAnyOrderEntriesOf(java.util.Map.of("ABROAD", 13, "ROLE_OR_STACK", 55));
        assertThat(last.sources())
                .extracting(LastRunSource::sourceId)
                .containsExactly("demo-newsletter", "manual-inbox");
        assertThat(last.sources().getFirst().announced()).isEqualTo(169);
        // Null and not zero: this source states no count to check against, which is most
        // of them, and a zero would read as "it announced nothing and delivered nothing".
        assertThat(last.sources().getLast().announced()).isNull();
    }

    @Test
    void picksTheRunThatStartedLastEvenWhenAnOlderOneFinishedAfterIt() {
        // A batched run is finished by the collector, which moves its `finished_at` past
        // runs that started after it. Ordered by that column, this morning's batch would
        // outrank this afternoon's ordinary pass — and the dashboard would report the wrong
        // morning, with entirely plausible numbers.
        Instant morning = Instant.now().minus(6, ChronoUnit.HOURS);
        Instant afternoon = Instant.now().minus(1, ChronoUnit.HOURS);
        run(morning, Instant.now(), "COMPLETE", "batched");
        run(afternoon, afternoon.plusSeconds(60), "COMPLETE", "synchronous");

        assertThat(runs.lastRun().orElseThrow().scoreModel()).isEqualTo("synchronous");
    }

    @Test
    void leavesThePreviousRunsSourceRowsOutOfThisOne() {
        // `source_run` carries no run id, so the window is the join. A lower bound that
        // reached back too far would show yesterday's documents under today's heading, and
        // the announced-versus-extracted check would be made against the wrong numbers.
        Instant yesterday = Instant.now().minus(1, ChronoUnit.DAYS);
        run(yesterday, yesterday.plusSeconds(60), "COMPLETE", "old");
        sourceRun(sourceId, yesterday.plusSeconds(5), 9, 900, 900, 900);

        Instant today = Instant.now().minus(2, ChronoUnit.MINUTES);
        run(today, today.plusSeconds(60), "COMPLETE", "new");
        sourceRun(sourceId, today.plusSeconds(5), 5, 169, 151, 169);

        var last = runs.lastRun().orElseThrow();

        assertThat(last.scoreModel()).isEqualTo("new");
        assertThat(last.sources()).singleElement().satisfies(source -> {
            assertThat(source.documents()).isEqualTo(5);
            assertThat(source.extracted()).isEqualTo(169);
        });
    }

    @Test
    void leavesAStillRunningPassOutOfTheOneItReports() {
        // The defect this replaced: the window had a lower bound and no upper one, on the
        // reasoning that "no later run exists to contribute rows above it". A pass in flight
        // breaks exactly that. Measured on the cluster — the panel listed every source twice,
        // because rows from a run begun two minutes later fell inside the reported one.
        //
        // The bound is the next run's `started_at`, and that column is the right one because
        // it never moves. `finished_at` does: the batch collector pushes it forward.
        Instant reported = Instant.now().minus(10, ChronoUnit.MINUTES);
        run(reported, reported.plusSeconds(60), "COMPLETE", "reported");
        sourceRun(sourceId, reported.plusSeconds(5), 5, 169, 151, 169);

        // Still going: a row, no finished_at, and its source rows already written.
        Instant inFlight = Instant.now().minus(2, ChronoUnit.MINUTES);
        open(inFlight);
        sourceRun(sourceId, inFlight.plusSeconds(5), 3, 42, 42, 42);

        var last = runs.lastRun().orElseThrow();

        // The finished one is reported, not the open one — a run that has done nothing yet
        // would put zeros on the dashboard under the heading "last run".
        assertThat(last.scoreModel()).isEqualTo("reported");
        assertThat(last.sources()).singleElement().satisfies(source -> {
            assertThat(source.documents()).isEqualTo(5);
            assertThat(source.extracted()).isEqualTo(169);
        });
    }

    @Test
    void reportsASourceThatExtractedFewerOffersThanItAnnounced() {
        // The one check nothing else can make. A selector that stops matching loses offers,
        // and fewer offers is indistinguishable from a quiet day on the market.
        Instant startedAt = Instant.now().minus(2, ChronoUnit.MINUTES);
        run(startedAt, startedAt.plusSeconds(30), "COMPLETE", "any");
        sourceRun(sourceId, startedAt.plusSeconds(1), 5, 140, 140, 169);

        assertThat(runs.lastRun().orElseThrow().sources().getFirst().complete()).isFalse();
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
                """,
                Long.class,
                Timestamp.from(startedAt),
                Timestamp.from(finishedAt),
                scoreModel,
                status);
    }

    private void stage(long runId, String stage, int removed) {
        jdbc.update("INSERT INTO pipeline_run_stage (run_id, stage, removed) VALUES (?, ?, ?)", runId, stage, removed);
    }

    /**
     * The row a run opens with: RUNNING, zeros, and no finished_at. See V15.
     */
    private void open(Instant startedAt) {
        jdbc.update(
                """
                        INSERT INTO pipeline_run (
                            started_at, ruleset_version, score_model, status,
                            documents, extracted, written, merged,
                            filter_considered, filter_passed,
                            enrich_considered, enriched, incomplete, from_cache, requests,
                            score_considered, scored, unscored, shortlisted, review, submitted,
                            packaged, digest_written)
                        VALUES (?, '1', 'in-flight', 'RUNNING', 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, false)
                        """,
                Timestamp.from(startedAt));
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
