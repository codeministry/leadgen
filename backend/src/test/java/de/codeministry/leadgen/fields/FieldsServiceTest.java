/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.fields;

import de.codeministry.leadgen.config.ConfigFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

/**
 * What the stage writes, what it leaves due, and what it makes scoring read again.
 *
 * <p>The extractor itself is stubbed: what it sends and what it reads back is
 * {@code FieldExtractorWireFormatTest}'s question, and this one is about the three things
 * around it that are easy to get wrong in silence — the stamp, the self-healing due query,
 * and the re-judge.
 */
@SpringBootTest
@Testcontainers
class FieldsServiceTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void configuration(DynamicPropertyRegistry registry) {
        registry.add("leadgen.config-dir", () -> ConfigFixtures.shippedDefaults().toString());
    }

    @Autowired
    private FieldsService fields;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private FieldExtractors extractors;

    @MockitoBean
    private FieldExtractor extractor;

    private long sourceId;

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM offer_score_reason");
        jdbc.update("DELETE FROM application_event");
        jdbc.update("DELETE FROM application");
        jdbc.update("DELETE FROM offer");
        jdbc.update("DELETE FROM source");
        sourceId =
            jdbc.queryForObject("INSERT INTO source (name, kind) VALUES ('test', 'file') RETURNING id", Long.class);
        given(extractor.model()).willReturn("a-model");
        given(extractors.current()).willReturn(Optional.of(extractor));
    }

    @Test
    void skipsWithoutAModelAndLeavesWhatThePatternsFound() {
        // Rules before model: without one the columns keep whatever the enrichment patterns
        // wrote, which is less than this stage would find and is not nothing. And nothing is
        // stamped, so the standing backlog becomes due the moment a key is configured — the
        // self-healing shape `score_model IS NULL` and `content_model IS NULL` already use.
        given(extractors.current()).willReturn(Optional.empty());
        long id = passed("Java Entwickler", LocalDate.of(2026, 10, 1), "6");

        assertThat(fields.run().considered()).isZero();

        assertThat(jdbc.queryForObject("SELECT duration FROM offer WHERE id = ?", String.class, id))
            .isEqualTo("6");
        assertThat(jdbc.queryForObject("SELECT fields_at FROM offer WHERE id = ?", Object.class, id))
            .isNull();
    }

    @Test
    void writesBothHalvesOfEveryPairAndStampsTheModel() {
        long id = passed("Java Entwickler", null, null);
        answers(new ExtractedFields(
            "ab sofort", null, "6 Monate mit Option", 6, "Bewerbungen bis 30.09.2026", LocalDate.of(2026, 9, 30)));

        var report = fields.run();

        assertThat(report.considered()).isEqualTo(1);
        assertThat(report.extracted()).isEqualTo(1);
        assertThat(report.stated()).isEqualTo(1);
        var row = jdbc.queryForMap(
            "SELECT start_text, starts_on, duration, duration_months, apply_by, apply_by_text, fields_model"
                + " FROM offer WHERE id = ?",
            id);
        assertThat(row.get("start_text")).isEqualTo("ab sofort");
        assertThat(row.get("starts_on")).isNull();
        assertThat(row.get("duration")).isEqualTo("6 Monate mit Option");
        assertThat(row.get("duration_months")).isEqualTo(6);
        assertThat(row.get("apply_by")).hasToString("2026-09-30");
        assertThat(row.get("fields_model")).isEqualTo("a-model");
    }

    @Test
    void makesScoringReadAgainWhenAValueActuallyChanged() {
        // Nulling `score_model` is the existing self-healing mechanism, not a fourth
        // staleness criterion invented here.
        long id = scored(passed("Ohne Angaben", null, null));
        answers(new ExtractedFields("ab sofort", null, null, null, null, null));

        assertThat(fields.run().rejudged()).isEqualTo(1);
        assertThat(scoreModelOf(id)).isNull();
    }

    @Test
    void leavesTheScoreAloneWhenTheExtractorOnlyConfirmsWhatWasAlreadyThere() {
        // A re-judge is a language-model call somebody pays for later, so it is bought only
        // when something moved. An advert the extractor merely confirms costs nothing.
        long id = scored(passed("Mit Angaben", LocalDate.of(2026, 10, 1), "6"));
        answers(new ExtractedFields(null, LocalDate.of(2026, 10, 1), "6", null, null, null));

        assertThat(fields.run().rejudged()).isZero();
        assertThat(scoreModelOf(id)).isEqualTo("a-judge");
    }

    @Test
    void leavesAnOfferDueWhenAConfiguredModelDidNotAnswer() {
        // The difference between "the advert states nothing" and "nothing came back". One is
        // a finished decision, the other has to come round again.
        long id = passed("Java Entwickler", null, null);
        given(extractor.extract(any())).willReturn(Optional.empty());

        var report = fields.run();

        assertThat(report.requests()).isEqualTo(1);
        assertThat(report.extracted()).isZero();
        assertThat(jdbc.queryForObject("SELECT fields_at FROM offer WHERE id = ?", Object.class, id))
            .isNull();
        // And it is handed to the next pass rather than remembered as read by nobody.
        answers(ExtractedFields.none());
        assertThat(fields.run().considered()).isEqualTo(1);
    }

    @Test
    void doesNotReadTheSameOfferTwiceUnderTheSameModel() {
        passed("Java Entwickler", null, null);
        answers(ExtractedFields.none());

        assertThat(fields.run().considered()).isEqualTo(1);
        assertThat(fields.run().considered()).isZero();
    }

    private void answers(ExtractedFields answer) {
        given(extractor.extract(any())).willReturn(Optional.of(answer));
    }

    private String scoreModelOf(long id) {
        return jdbc.queryForObject("SELECT score_model FROM offer WHERE id = ?", String.class, id);
    }

    private long scored(long id) {
        jdbc.update("UPDATE offer SET score_value = 80, score_band = 'REVIEW', score_model = 'a-judge' WHERE id = ?", id);
        return id;
    }

    private long passed(String title, LocalDate startsOn, String duration) {
        return jdbc.queryForObject(
            """
                INSERT INTO offer (source_id, external_id, title, description, url, fingerprint, status,
                                   full_text, starts_on, duration)
                VALUES (?, ?, ?, 'Kurzbeschreibung.', 'https://example.invalid/1', ?, 'PASSED',
                        'Wir suchen ab sofort.', ?, ?)
                RETURNING id
                """,
            Long.class,
            sourceId,
            title + System.nanoTime(),
            title,
            title.toLowerCase(),
            startsOn,
            duration);
    }
}
