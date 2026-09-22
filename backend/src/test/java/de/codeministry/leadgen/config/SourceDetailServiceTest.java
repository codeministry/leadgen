/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import de.codeministry.leadgen.Databases;
import java.time.LocalDate;
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
 * One source, opened: what defines it, and when its numbers last moved.
 */
@SpringBootTest
@Testcontainers
class SourceDetailServiceTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = Databases.postgres();

    @DynamicPropertySource
    static void configuration(DynamicPropertyRegistry registry) {
        registry.add(
                "leadgen.config-dir", () -> ConfigFixtures.shippedDefaults().toString());
    }

    @Autowired
    private SourceDetailService details;

    @Autowired
    private JdbcTemplate jdbc;

    /**
     * Built here rather than injected: this context has no `ObjectMapper` bean, and what is
     * under test is the record's own annotation rather than anybody's mapper configuration.
     * The time module is what a bare mapper is missing.
     */
    private final com.fasterxml.jackson.databind.ObjectMapper mapper =
            com.fasterxml.jackson.databind.json.JsonMapper.builder()
                    .addModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                    .build();

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM source_run");
        jdbc.update("DELETE FROM source");
    }

    @Test
    void answersNothingForAnIdNobodyConfigured() {
        assertThat(details.detail("no-such-source", 30)).isEmpty();
    }

    @Test
    void neverTreatsTheIdAsAPath() {
        // The id selects from the snapshot's own list; it is never joined to a directory. If
        // that ever changes, this is the test that says so before a stranger's filesystem does.
        assertThat(details.detail("../../etc/passwd", 30)).isEmpty();
        assertThat(details.detail("../sources.yaml", 30)).isEmpty();
    }

    @Test
    void carriesTheBlockThatDefinesTheSourceWithItsComments() {
        var detail = details.detail("manual-inbox", 30).orElseThrow();

        assertThat(detail.kind()).isEqualTo("file");
        assertThat(detail.enabled()).isTrue();
        assertThat(detail.file().name()).isEqualTo("sources.yaml");
        assertThat(detail.block().text()).contains("- id: manual-inbox").contains("strategy: markdown-frontmatter");
        // The comments are most of what makes a block worth showing at all.
        assertThat(detail.block().text()).contains("This is a `file` source and not a new mechanism");
        assertThat(detail.block().firstLine()).isLessThan(detail.block().lastLine());
    }

    @Test
    void carriesTheConnectionBlockASourceNamesAndNothingWhereItNamesNone() {
        // An imap source is half-defined somewhere else in the file, and a reader sent to go
        // and find it finds the credentials rather than the settings.
        var imap = details.detail("sample-newsletter", 30).orElseThrow();
        assertThat(imap.connection()).isNotNull();
        assertThat(imap.connection().text()).contains("- id: mailbox-primary").contains("type: imap");

        assertThat(details.detail("manual-inbox", 30).orElseThrow().connection())
                .isNull();
    }

    @Test
    void walksTheRunsNewestFirstAndSaysWhenExtractedLastChanged() {
        long id = source("manual-inbox");
        ran(id, "2026-09-11", 4, 157, 157, null);
        ran(id, "2026-09-12", 4, 169, 169, null);
        ran(id, "2026-09-13", 5, 169, 169, null);
        ran(id, "2026-09-14", 5, 169, 169, null);

        var detail = details.detail("manual-inbox", 30).orElseThrow();

        assertThat(detail.runs())
                .extracting(SourceRun::ranOn)
                .containsExactly(
                        LocalDate.parse("2026-09-14"),
                        LocalDate.parse("2026-09-13"),
                        LocalDate.parse("2026-09-12"),
                        LocalDate.parse("2026-09-11"));
        assertThat(detail.trend().extractedNow()).isEqualTo(169);
        assertThat(detail.trend().extractedChangedOn()).isEqualTo(LocalDate.parse("2026-09-12"));
        assertThat(detail.trend().extractedBefore()).isEqualTo(157);
    }

    @Test
    void reportsNoChangeAtAllRatherThanOneAtTheWindowsEdge() {
        // The edge of what we looked at is not an event. "Changed on the oldest row I fetched"
        // is the panel inventing one.
        long id = source("manual-inbox");
        ran(id, "2026-09-13", 5, 169, 169, null);
        ran(id, "2026-09-14", 5, 169, 169, null);

        var trend = details.detail("manual-inbox", 30).orElseThrow().trend();

        assertThat(trend.extractedChangedOn()).isNull();
        assertThat(trend.extractedBefore()).isNull();
        assertThat(trend.extractedNow()).isEqualTo(169);
    }

    @Test
    void saysWhenAnnouncedLastDivergedAndWhenNoCountWasEverStated() {
        long id = source("manual-inbox");
        ran(id, "2026-09-12", 5, 169, 169, 169);
        ran(id, "2026-09-13", 5, 157, 157, 169);
        ran(id, "2026-09-14", 5, 169, 169, 169);

        var trend = details.detail("manual-inbox", 30).orElseThrow().trend();
        assertThat(trend.announcedStated()).isTrue();
        assertThat(trend.divergedOn()).isEqualTo(LocalDate.parse("2026-09-13"));
        assertThat(trend.missing()).isEqualTo(12);

        jdbc.update("UPDATE source_run SET announced = NULL");
        var silent = details.detail("manual-inbox", 30).orElseThrow().trend();
        assertThat(silent.announcedStated()).isFalse();
        assertThat(silent.divergedOn()).isNull();
    }

    @Test
    void carriesWrittenBesideExtractedBecauseNothingElseShowsIt() {
        // Recorded on every run since V9 and displayed nowhere until now. The gap is what
        // re-reading a newsletter looks like.
        long id = source("manual-inbox");
        ran(id, "2026-09-14", 14, 1289, 1280, 1289);

        var run = details.detail("manual-inbox", 30).orElseThrow().runs().getFirst();

        assertThat(run.extracted()).isEqualTo(1289);
        assertThat(run.written()).isEqualTo(1280);
        assertThat(run.missing()).isZero();
    }

    @Test
    void putsTheMissingCountOnTheWireAndNotOnlyInJava() {
        // Jackson builds a record's JSON from its components, and `missing()` is a method — so
        // it was absent from the answer, the browser read `undefined`, and the panel painted an
        // "undefined missing" badge on every run where the count actually matched. Every
        // hand-written fixture had supplied the field, which is exactly what a fixture cannot
        // check. The assertion is therefore on the serialised form.
        long id = source("manual-inbox");
        ran(id, "2026-09-14", 5, 157, 157, 169);

        var detail = details.detail("manual-inbox", 30).orElseThrow();
        // The application's own mapper and not a bare one: what is under test is the JSON this
        // service actually answers with, and a mapper without the JSR-310 module is not it.
        String json =
                assertDoesNotThrow(() -> mapper.writeValueAsString(detail.runs().getFirst()));

        assertThat(json).contains("\"missing\":12");
    }

    @Test
    void clampsHowManyRunsACallerCanAskFor() {
        long id = source("manual-inbox");
        for (int day = 1; day <= 12; day++) {
            ran(id, "2026-09-%02d".formatted(day), 5, 169, 169, null);
        }

        assertThat(details.detail("manual-inbox", 3).orElseThrow().runs()).hasSize(3);
        assertThat(details.detail("manual-inbox", 0).orElseThrow().runs()).hasSize(12);
        assertThat(details.detail("manual-inbox", 10_000).orElseThrow().runs()).hasSize(12);
    }

    @Test
    void answersASourceThatHasNeverRun() {
        // The configuration is the list, not the database: a source nobody has run still has a
        // block, and the panel has to say so rather than fail.
        var detail = details.detail("manual-inbox", 30).orElseThrow();

        assertThat(detail.runs()).isEmpty();
        assertThat(detail.trend().extractedNow()).isNull();
        assertThat(detail.block()).isNotNull();
    }

    private long source(String name) {
        return jdbc.queryForObject("INSERT INTO source (name, kind) VALUES (?, 'file') RETURNING id", Long.class, name);
    }

    private void ran(long sourceId, String day, int documents, int extracted, int written, Integer announced) {
        jdbc.update(
                """
                INSERT INTO source_run (source_id, ran_at, documents, extracted, written, announced)
                VALUES (?, ?::timestamptz, ?, ?, ?, ?)
                """,
                sourceId,
                day + " 06:00:00+02",
                documents,
                extracted,
                written,
                announced);
    }
}
