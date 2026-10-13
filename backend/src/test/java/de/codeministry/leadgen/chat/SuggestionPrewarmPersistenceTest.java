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

import de.codeministry.leadgen.Databases;
import de.codeministry.leadgen.chat.suggest.Suggestion;
import de.codeministry.leadgen.chat.suggest.SuggestionScope;
import de.codeministry.leadgen.chat.suggest.SuggestionService;
import de.codeministry.leadgen.chat.suggest.SuggestionWarmup;
import de.codeministry.leadgen.config.ConfigRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * ISC-531: a phrasing outlives the process. The first test phrases a snapshot and throws its
 * application context away; the second starts a fresh one on the same database, so the in-memory
 * map is empty and only the table can answer.
 */
@SpringBootTest
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SuggestionPrewarmPersistenceTest {

    private static final ModelStub MODEL = ModelStub.start();

    /** Created once, never inside the property supplier — see {@code LlmBudgetTest}. */
    private static final Path STUBBED = MODEL.configuration().resolve("pipeline.yaml");

    private static final String ORIGINAL = read(STUBBED);

    private static final List<String> PHRASED_EN =
            List.of("Which of the 0 shortlisted offers fits?", "How are the 0 open applications doing?");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = Databases.postgres();

    @DynamicPropertySource
    static void configuration(DynamicPropertyRegistry registry) {
        registry.add("leadgen.config-dir", () -> STUBBED.getParent().toString());
    }

    @Autowired
    private SuggestionService suggestions;

    @Autowired
    private SuggestionWarmup warmup;

    @Autowired
    private ConfigRegistry config;

    @Autowired
    private JdbcTemplate jdbc;

    @AfterAll
    static void stop() {
        MODEL.close();
    }

    @Test
    @Order(1)
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void aPhrasingIsStoredBeyondTheMap() {
        jdbc.execute("TRUNCATE application_event, application, pipeline_run, offer, source CASCADE");
        run();
        MODEL.enqueue(ModelStub.text(Duration.ZERO, phrased()));

        suggestions.suggestions(SuggestionScope.NONE, "en");
        awaitIdle();
        List<Suggestion> shown = suggestions.suggestions(SuggestionScope.NONE, "en");

        assertThat(shown).extracting(Suggestion::text).containsExactlyElementsOf(PHRASED_EN);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_suggestion_phrasing", Integer.class))
                .isEqualTo(1);
        assertThat(MODEL.bodies()).hasSize(1);
    }

    @Test
    @Order(2)
    void aFreshContextAnswersTheStoredPhrasingWithoutTheModelAndAnyChangeIsANewKey() throws IOException {
        MODEL.reset();

        List<Suggestion> restarted = suggestions.suggestions(SuggestionScope.NONE, "en");
        awaitIdle();
        int afterRestart = MODEL.bodies().size();

        List<Suggestion> otherLanguage = suggestions.suggestions(SuggestionScope.NONE, "de");
        awaitIdle();
        jdbc.update("INSERT INTO source (name, kind) VALUES ('count', 'file')");
        jdbc.update("""
                INSERT INTO offer (source_id, external_id, title, url, fingerprint, status, score_band,
                                   score_value, tags, ingested_at)
                SELECT id, 'count', 'Offer count', 'https://example.invalid/count', 'count', 'PASSED',
                       'SHORTLISTED', 95, '{}'::text[], now()
                  FROM source WHERE name = 'count'
                """);
        List<Suggestion> otherCount = suggestions.suggestions(SuggestionScope.NONE, "en");
        awaitIdle();
        jdbc.update("DELETE FROM offer WHERE external_id = 'count'");
        configure(ORIGINAL.replace("chat: " + ModelStub.MODEL, "chat: another-chat-model"));
        List<Suggestion> otherModel = suggestions.suggestions(SuggestionScope.NONE, "en");
        awaitIdle();
        configure(ORIGINAL);

        assertThat(restarted).extracting(Suggestion::text).containsExactlyElementsOf(PHRASED_EN);
        assertThat(afterRestart).isZero();
        assertThat(otherLanguage)
                .extracting(Suggestion::text)
                .containsExactly(
                        "0 Angebote stehen auf der Shortlist. Welches passt am besten zu meinem Profil?",
                        "0 Bewerbungen sind offen. Wo steht jede davon?");
        assertThat(otherCount).extracting(Suggestion::text).doesNotContainAnyElementsOf(PHRASED_EN);
        assertThat(otherCount).extracting(Suggestion::count).contains(1);
        assertThat(otherModel)
                .extracting(Suggestion::text)
                .containsExactly(
                        "0 offers are on the shortlist. Which fits my profile best?",
                        "0 applications are open. Where does each one stand?");
    }

    /** Waits for the background phrasing to finish. */
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

    private static String phrased() {
        return "{\"shortlist\":\"" + PHRASED_EN.get(0) + "\",\"openApplications\":\"" + PHRASED_EN.get(1) + "\"}";
    }

    private void run() {
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO pipeline_run (
                    started_at, finished_at, ruleset_version, score_model, status,
                    documents, extracted, written, merged,
                    filter_considered, filter_passed,
                    enrich_considered, enriched, incomplete, from_cache, requests,
                    score_considered, scored, unscored, shortlisted, review, submitted,
                    packaged, digest_written)
                VALUES (?, ?, '1', 'm', 'COMPLETE', 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, true)
                """, Timestamp.from(now.minusSeconds(60)), Timestamp.from(now));
    }

    private void configure(String pipeline) throws IOException {
        Files.writeString(STUBBED, pipeline, StandardCharsets.UTF_8);
        config.reload();
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
