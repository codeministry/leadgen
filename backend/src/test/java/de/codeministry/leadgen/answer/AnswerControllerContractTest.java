/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.answer;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.codeministry.leadgen.Databases;
import de.codeministry.leadgen.config.ConfigFixtures;
import de.codeministry.leadgen.config.ConfigRegistry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * ISC-371, the seam: {@code POST /api/v1/offers/{id}/answer?question=&model=} as the
 * measurement script and the service are both built against it.
 *
 * <p>A whole context over a real database rather than a web slice, because three of the
 * refusals are the service's and not the controller's: the allowlist is read from the
 * configuration, and "no such offer" and "no text to read" are read from the table. A slice
 * with the service mocked would pin the mapping and leave the contract itself unpinned.
 *
 * <p>No model is reachable from here, and none has to be: the configuration names two
 * candidates so the allowlist has something to check against, and points the endpoint at an
 * address nothing listens on. What a real answer contains is the service probe's question.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class AnswerControllerContractTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = Databases.postgres();

    /** Created once: a {@code @DynamicPropertySource} supplier may run more than once. */
    private static final Path CONFIG = twoCandidates();

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ConfigRegistry config;

    private long sourceId;

    @DynamicPropertySource
    static void configuration(DynamicPropertyRegistry registry) {
        registry.add("leadgen.config-dir", CONFIG::toString);
    }

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM offer_score_reason");
        jdbc.update("DELETE FROM offer");
        jdbc.update("DELETE FROM source");
        sourceId =
                jdbc.queryForObject("INSERT INTO source (name, kind) VALUES ('test', 'file') RETURNING id", Long.class);
    }

    @Test
    void answersWithTheSixFieldsTheScriptReads() throws IOException {
        long id = answered();

        JsonNode body = ok(id, "BLOCKS", "other-model");

        List<String> names = new ArrayList<>();
        body.fieldNames().forEachRemaining(names::add);
        // All six present even when a value is null, because the script reads them by name and
        // a missing key and a null answer would otherwise be the same thing to it.
        assertThat(names).containsExactlyInAnyOrder("question", "model", "answer", "stored", "raw", "millis");
        // The wire name comes back as it is spelt on the wire, whatever case it was sent in.
        assertThat(body.get("question").asText()).isEqualTo(AnswerQuestion.BLOCKS.key());
        assertThat(body.get("model").asText()).isEqualTo("other-model");
        assertThat(body.get("millis").isIntegralNumber()).isTrue();
        assertThat(body.get("stored").isObject()).isTrue();
    }

    @Test
    void storesOnlyTheBlocksAModelDecidedInIndexOrder() throws IOException {
        // Index 0 was decided by a rule, so no model was ever asked about it and it cannot be
        // compared. The stored column is written out of order on purpose.
        JsonNode stored = ok(answered(), "blocks", "test-model").get("stored");

        assertThat(stored.toString())
                .isEqualTo("{\"blocks\":[{\"index\":1,\"kind\":\"CONTENT\"},{\"index\":2,\"kind\":\"LEGAL\"}]}");
    }

    @Test
    void storesTheThreeFieldsAsTextAndResolvedValue() throws IOException {
        JsonNode stored = ok(answered(), "fields", "test-model").get("stored");

        assertThat(stored.toString())
                .isEqualTo("{\"start\":{\"text\":\"ab sofort\",\"date\":\"2026-10-01\"},"
                        + "\"duration\":{\"text\":\"12 Monate\",\"months\":12},"
                        + "\"deadline\":{\"text\":null,\"date\":null}}");
    }

    @Test
    void storesOnlyTheJudgedFactorsAndAnAbsentOneAsZero() throws IOException {
        // The rule-scored factor and the topic row are not the judge's answer, so they are not
        // what a candidate judge is compared against.
        JsonNode stored = ok(answered(), "judge", "test-model").get("stored");

        assertThat(stored.toString())
                .isEqualTo("{\"reasons\":[{\"factor\":\"role_fit\",\"points\":15},"
                        + "{\"factor\":\"stack_mismatch_dominant\",\"points\":0},"
                        + "{\"factor\":\"role_mismatch\",\"points\":0},"
                        + "{\"factor\":\"vague_description\",\"points\":-10}]}");
    }

    @Test
    void answers409WhenNothingIsStoredToCompareAgainstAndChangesNoRow() {
        // Text, but never segmented, never extracted and never judged: an answer bought now
        // would measure nothing, so it is refused before the budget is asked.
        long id = offer("Java 21 und Spring Boot.");
        String before = rows();

        for (String question : List.of("blocks", "fields", "judge")) {
            assertThat(mvc.post()
                            .uri("/api/v1/offers/{id}/answer", id)
                            .param("question", question)
                            .param("model", "test-model"))
                    .hasStatus(HttpStatus.CONFLICT)
                    .bodyText()
                    .isEqualTo("offer " + id + " has no stored answer to " + question);
        }
        assertThat(rows()).isEqualTo(before);
    }

    @Test
    void changesNoRowWhenItAnswers() throws IOException {
        long id = answered();
        String before = rows();

        for (String question : List.of("blocks", "fields", "judge")) {
            ok(id, question, "test-model");
        }

        assertThat(rows()).isEqualTo(before);
    }

    @Test
    void parsesTheQuestionWhateverItsCase() {
        assertThat(AnswerQuestion.of(" Judge ")).isEqualTo(AnswerQuestion.JUDGE);
        assertThat(AnswerQuestion.of("fields").key()).isEqualTo("fields");
    }

    @Test
    void refusesAModelNobodyConfigured() {
        // The endpoint behind the name is billed per token, so an arbitrary string must not
        // decide what gets bought.
        long id = offer("Java 21 und Spring Boot.");

        assertThat(mvc.post()
                        .uri("/api/v1/offers/{id}/answer", id)
                        .param("question", "judge")
                        .param("model", "somebody-elses-model"))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyText()
                .contains("somebody-elses-model");
    }

    @Test
    void refusesARequestThatNamesNoModel() {
        // A comparison with the model left out would answer with the default and be recorded
        // as a candidate's agreement.
        long id = offer("Java 21 und Spring Boot.");

        assertThat(mvc.post()
                        .uri("/api/v1/offers/{id}/answer", id)
                        .param("question", "judge")
                        .param("model", " "))
                .hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void refusesAQuestionNobodyDefined() {
        long id = offer("Java 21 und Spring Boot.");

        assertThat(mvc.post()
                        .uri("/api/v1/offers/{id}/answer", id)
                        .param("question", "rate")
                        .param("model", "test-model"))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyText()
                .contains("blocks, fields, judge");
    }

    @Test
    void answers404ForAnOfferThatIsNotThere() {
        assertThat(mvc.post()
                        .uri("/api/v1/offers/{id}/answer", 999_999L)
                        .param("question", "fields")
                        .param("model", "test-model"))
                .hasStatus(HttpStatus.NOT_FOUND)
                .bodyText()
                .contains("999999");
    }

    @Test
    void answers409ForAnOfferWithNoTextToRead() {
        // Asking anyway would measure a model against the newsletter teaser.
        long id = offer(null);

        assertThat(mvc.post()
                        .uri("/api/v1/offers/{id}/answer", id)
                        .param("question", "fields")
                        .param("model", "test-model"))
                .hasStatus(HttpStatus.CONFLICT)
                .bodyText()
                .isEqualTo("offer " + id + " has no fetched text to ask a model about");
    }

    private JsonNode ok(long id, String question, String model) throws IOException {
        var result = mvc.post()
                .uri("/api/v1/offers/{id}/answer", id)
                .param("question", question)
                .param("model", model)
                .exchange();
        assertThat(result).hasStatusOk();
        return JSON.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    /** Every row a call could conceivably touch, as text, to compare before and after. */
    private String rows() {
        return jdbc.queryForObject("""
                SELECT coalesce((SELECT string_agg(row_to_json(o)::text, '|' ORDER BY o.id) FROM offer o), '')
                    || '#' || coalesce((SELECT string_agg(row_to_json(r)::text, '|' ORDER BY r.id)
                                        FROM offer_score_reason r), '')
                    || '#' || coalesce((SELECT string_agg(row_to_json(l)::text, '|' ORDER BY l.portal, l.digest)
                                        FROM content_block_label l), '')
                """, String.class);
    }

    /** An offer each of the three stages has answered, as they write it. */
    private long answered() {
        long id = offer("Java 21 und Spring Boot, 12 Monate, ab sofort.");
        jdbc.update("""
                UPDATE offer SET
                    content_blocks = ?::jsonb,
                    start_text = 'ab sofort', starts_on = DATE '2026-10-01',
                    duration = '12 Monate', duration_months = 12,
                    fields_model = 'test-model', score_model = 'test-model', ruleset_version = ?
                WHERE id = ?
                """, """
                [{"index":2,"text":"Datenschutzhinweis.","kind":"LEGAL","reason":"privacy","by":"MODEL"},
                 {"index":0,"text":"Jetzt bewerben","kind":"CHROME","reason":"button","by":"RULE"},
                 {"index":1,"text":"Java 21 und Spring Boot, 12 Monate, ab sofort.","kind":"CONTENT",
                  "reason":null,"by":"MODEL"}]
                """, String.valueOf(config.snapshot().rules().version()), id);
        reason(id, "core_skill_overlap", 45, null, 0);
        reason(id, "role_fit", 15, null, 1);
        reason(id, "vague_description", -10, null, 2);
        reason(id, "interest_fit", 5, "kotlin", 3);
        return id;
    }

    private void reason(long offerId, String factor, int points, String topic, int position) {
        jdbc.update(
                "INSERT INTO offer_score_reason (offer_id, factor, label, points, position, topic)"
                        + " VALUES (?, ?, 'a label', ?, ?, ?)",
                offerId,
                factor,
                points,
                position,
                topic);
    }

    private long offer(String fullText) {
        return jdbc.queryForObject("""
            INSERT INTO offer (source_id, external_id, title, url, fingerprint, status, full_text)
            VALUES (?, ?, 'Senior Java Entwickler (m/w/d)', 'https://example.invalid/x', 'fp', 'PASSED', ?)
            RETURNING id
            """, Long.class, sourceId, "ext-" + System.nanoTime(), fullText);
    }

    /**
     * The shipped defaults with two scoring candidates. Every other placeholder resolves from
     * {@code ConfigFixtures.NEUTRAL_PLACEHOLDERS}, never from the machine.
     */
    private static Path twoCandidates() {
        try {
            Path dir = Files.createTempDirectory("leadgen-answer-contract");
            dir.toFile().deleteOnExit();
            ConfigFixtures.materialize(dir);
            Path pipeline = dir.resolve("pipeline.yaml");
            String text = Files.readString(pipeline, StandardCharsets.UTF_8);
            text = set(text, "provider", "openai-compatible");
            text = set(text, "base_url", "http://127.0.0.1:9");
            text = set(text, "api_key", "test-key");
            text = set(text, "scoring", "test-model");
            text = set(text, "scoring_options", "other-model");
            Files.writeString(pipeline, text, StandardCharsets.UTF_8);
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** One scalar of the shipped {@code pipeline.yaml}, addressed by key whatever its padding. */
    private static String set(String yaml, String key, String value) {
        Matcher matcher =
                Pattern.compile("(?m)^([ \\t]*)" + Pattern.quote(key) + ":.*$").matcher(yaml);
        if (!matcher.find()) {
            throw new IllegalStateException(
                    "no `" + key + ":` in the shipped pipeline.yaml — the fixture and the file have drifted");
        }
        return matcher.replaceFirst("$1" + key + ": " + Matcher.quoteReplacement(value));
    }
}
