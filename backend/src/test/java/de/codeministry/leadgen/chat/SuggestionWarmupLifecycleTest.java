/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

import de.codeministry.leadgen.Databases;
import de.codeministry.leadgen.analytics.PipelineRunRecorder;
import de.codeministry.leadgen.application.ApplicationService;
import de.codeministry.leadgen.application.ApplicationStatus;
import de.codeministry.leadgen.application.ApplicationUpdate;
import de.codeministry.leadgen.chat.suggest.SuggestionScope;
import de.codeministry.leadgen.chat.suggest.SuggestionService;
import de.codeministry.leadgen.chat.suggest.SuggestionWarmup;
import de.codeministry.leadgen.event.DataChanged;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Spec 028: the warm-up's two ends, which no shared context can hold.
 *
 * <p>Before the first open since start, a data change prepares nothing: no model request and no
 * call from the chat's day, and the first open arms it (ISC-536). That needs a context no case has
 * opened the chat in yet, so it runs first.
 *
 * <p>After the warm-up was shut down, a data change — a run finishing while the context closes — is
 * dropped quietly. Before, the warm-up's own executor refused the task, the refusal reached the
 * writer through the synchronous listener, and the writer logged a run it had recorded as one it
 * had not (ISC-540). That shuts the warm-up down, so it runs last and the context is discarded.
 */
@SpringBootTest
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SuggestionWarmupLifecycleTest {

    private static final ModelStub MODEL = ModelStub.start();

    private static final Path STUBBED = MODEL.configuration().resolve("pipeline.yaml");

    private static final Duration QUIET = Duration.ofMillis(300);

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = Databases.postgres();

    @DynamicPropertySource
    static void configuration(DynamicPropertyRegistry registry) {
        registry.add("leadgen.config-dir", () -> STUBBED.getParent().toString());
        registry.add("leadgen.chat.suggestion-quiet-window", () -> QUIET.toString());
    }

    @Autowired
    private SuggestionWarmup warmup;

    @Autowired
    private PipelineRunRecorder recorder;

    @Autowired
    private ApplicationEventPublisher events;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private SuggestionService suggestions;

    @Autowired
    private ApplicationService applications;

    @AfterAll
    static void stop() {
        MODEL.close();
    }

    @Test
    @Order(1)
    void beforeTheFirstOpenADataChangePreparesNothingAndTheFirstOpenArmsIt() throws InterruptedException {
        MODEL.enqueue(ModelStub.text(Duration.ZERO, PHRASED));
        MODEL.enqueue(ModelStub.text(Duration.ZERO, PHRASED));
        awaitingRun();
        recorder.complete(0, 0, false);
        long application = applications.open(offer(), ApplicationStatus.NEW);
        for (ApplicationStatus next : List.of(
                ApplicationStatus.SHORTLISTED,
                ApplicationStatus.NEW,
                ApplicationStatus.SHORTLISTED,
                ApplicationStatus.NEW)) {
            applications.update(application, new ApplicationUpdate(next, null, null, null, null, null));
        }
        Thread.sleep(QUIET.multipliedBy(3).toMillis());
        awaitIdle();
        int beforeAnyOpen = MODEL.bodies().size();
        int chatCallsBeforeAnyOpen = chatCalls();

        // A pinned open misses as before, is phrased in the background, and arms the warm-up.
        suggestions.suggestions(new SuggestionScope(42L, List.of()), "en");
        awaitIdle();
        int afterThePinnedOpen = MODEL.bodies().size();
        applications.update(
                application, new ApplicationUpdate(ApplicationStatus.SHORTLISTED, null, null, null, null, null));
        awaitIdle();

        assertThat(beforeAnyOpen).isZero();
        assertThat(chatCallsBeforeAnyOpen).isZero();
        assertThat(afterThePinnedOpen).isEqualTo(1);
        assertThat(MODEL.bodies())
                .as("armed: a change now prepares the next open")
                .hasSize(2);
    }

    @Test
    @Order(2)
    void aDataChangeAfterShutdownIsDroppedWithoutReachingTheWriter(CapturedOutput output) {
        MODEL.reset();
        warmup.destroy();
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO pipeline_run (
                    started_at, finished_at, ruleset_version, score_model, status,
                    documents, extracted, written, merged,
                    filter_considered, filter_passed,
                    enrich_considered, enriched, incomplete, from_cache, requests,
                    score_considered, scored, unscored, shortlisted, review, submitted,
                    packaged, digest_written)
                VALUES (?, ?, '1', 'm', 'AWAITING_BATCH', 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, true)
                """, Timestamp.from(now.minusSeconds(60)), Timestamp.from(now));

        assertThatNoException().isThrownBy(() -> events.publishEvent(new DataChanged("test, after shutdown")));
        recorder.complete(0, 0, false);

        assertThat(jdbc.queryForObject("SELECT status FROM pipeline_run ORDER BY id DESC LIMIT 1", String.class))
                .isEqualTo("COMPLETE");
        assertThat(output.getOut() + output.getErr())
                .doesNotContain("was not completed in the history")
                .doesNotContain("The run was not recorded");
        assertThat(warmup.idle()).isTrue();
        assertThat(MODEL.bodies()).isEmpty();
    }

    private static final String PHRASED = "{\"shortlist\":\"Which of the 0 shortlisted offers fits?\","
            + "\"openApplications\":\"How are the 0 open applications doing?\"}";

    private void awaitingRun() {
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO pipeline_run (
                    started_at, finished_at, ruleset_version, score_model, status,
                    documents, extracted, written, merged,
                    filter_considered, filter_passed,
                    enrich_considered, enriched, incomplete, from_cache, requests,
                    score_considered, scored, unscored, shortlisted, review, submitted,
                    packaged, digest_written)
                VALUES (?, ?, '1', 'm', 'AWAITING_BATCH', 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, true)
                """, Timestamp.from(now.minusSeconds(60)), Timestamp.from(now));
    }

    private long offer() {
        long source = jdbc.queryForObject(
                "INSERT INTO source (name, kind) VALUES ('lifecycle', 'file') RETURNING id", Long.class);
        return jdbc.queryForObject("""
                INSERT INTO offer (source_id, external_id, title, url, fingerprint, status, score_band,
                                   score_value, tags, ingested_at)
                VALUES (?, 'lifecycle', 'Offer lifecycle', 'https://example.invalid/lifecycle', 'lifecycle',
                        'PASSED', 'SHORTLISTED', 95, '{}'::text[], now())
                RETURNING id
                """, Long.class, source);
    }

    private int chatCalls() {
        return jdbc.queryForObject("SELECT coalesce(sum(calls), 0) FROM chat_call_budget", Integer.class);
    }

    private void awaitIdle() {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (!warmup.idle()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("the suggestion warm-up did not finish within 30 s");
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }
}
