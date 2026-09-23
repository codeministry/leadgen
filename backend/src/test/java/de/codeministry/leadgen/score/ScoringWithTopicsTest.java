/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.score;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import de.codeministry.leadgen.Databases;
import de.codeministry.leadgen.config.ConfigFixtures;
import de.codeministry.leadgen.config.ConfigRegistry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
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
 * F29: an interest topic lifts a score by an absolute bonus, a disinterest topic sinks it
 * by an absolute penalty, the matched topic is stored beside the reason, and a changed
 * profile moves the scores on the next run without asking the judge again.
 *
 * <p>The shipped profile carries one placeholder topic per list, `Example topic` (weight 8,
 * alias `Example field`) and `Example unwanted topic` (weight 6, alias `Unwanted field`),
 * and the shipped rules weigh them at `interest_fit: 15` and `disinterest_fit: -20`. So a
 * match is worth 12 points either way, and the judge is stubbed to give no role fit so no
 * total reaches the clamp at 100 and hides the difference. The advert texts avoid the word
 * `Example`: the placeholder industry matches it, and a second factor firing would move the
 * share and blur the exact difference these tests measure.
 */
@SpringBootTest
@Testcontainers
class ScoringWithTopicsTest {

    private static final WireMockServer MODEL;
    private static final Path CONFIG;

    static {
        MODEL = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        MODEL.start();
        CONFIG = configPointingAtTheStub();
    }

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = Databases.postgres();

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private ScoringService scoring;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ConfigRegistry config;

    private long sourceId;
    private String shippedProfile;
    private String shippedRules;

    @DynamicPropertySource
    static void configuration(DynamicPropertyRegistry registry) {
        registry.add("leadgen.config-dir", CONFIG::toString);
    }

    @AfterAll
    static void stopModel() {
        MODEL.stop();
    }

    @BeforeEach
    void reset() throws IOException {
        jdbc.update("DELETE FROM offer_score_reason");
        jdbc.update("DELETE FROM offer");
        jdbc.update("DELETE FROM source");
        sourceId =
                jdbc.queryForObject("INSERT INTO source (name, kind) VALUES ('test', 'file') RETURNING id", Long.class);
        MODEL.resetAll();
        answers("{\"reasons\":[{\"factor\":\"role_fit\",\"label\":\"not the target role\",\"points\":0}]}");
        shippedProfile = Files.readString(CONFIG.resolve("skill-profile.yaml"));
        shippedRules = Files.readString(CONFIG.resolve("matching-rules.yaml"));
    }

    @AfterEach
    void restore() throws IOException {
        Files.writeString(CONFIG.resolve("skill-profile.yaml"), shippedProfile);
        Files.writeString(CONFIG.resolve("matching-rules.yaml"), shippedRules);
        config.reload();
    }

    @Test
    void anInterestTopicLiftsTheTotalByExactlyItsBonusAndNamesItself() {
        long plain = offer("Senior Java Entwickler (m/w/d)", "Java 21 und Spring Boot");
        long topical = offer("Senior Java Entwickler (m/w/d)", "Java 21 und Spring Boot, ein Beispielthema");

        scoring.run();

        assertThat(scoreOf(topical) - scoreOf(plain)).isEqualTo(12);
        assertThat(topicRows(topical)).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("factor", "interest_fit");
            assertThat(row).containsEntry("topic", "Example topic");
            assertThat(row).containsEntry("points", 12);
            assertThat(row).containsEntry("max_points", 0);
        });
        assertThat(topicRows(plain)).isEmpty();
    }

    @Test
    void aDisinterestTopicIsChargedOnceHoweverOftenTheAdvertNamesIt() {
        long plain = offer("Senior Java Entwickler (m/w/d)", "Java 21 und Spring Boot");
        long unwanted = offer(
                "Senior Java Entwickler (m/w/d)",
                "Java 21 und Spring Boot. Unwanted field, dann mehr Unwanted field und noch einmal Unwanted field");

        scoring.run();

        assertThat(scoreOf(unwanted) - scoreOf(plain)).isEqualTo(-12);
        assertThat(topicRows(unwanted)).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("factor", "disinterest_fit");
            assertThat(row).containsEntry("topic", "Example unwanted topic");
            assertThat(row).containsEntry("points", -12);
        });
    }

    @Test
    void aChangedProfileMovesTheScoreOnTheNextRunWithoutAskingTheJudge() throws IOException {
        long id = offer("Senior Java Entwickler (m/w/d)", "Java 21 und Spring Boot, Schwerpunkt Datenplattform");
        scoring.run();
        int before = scoreOf(id);
        MODEL.verify(1, postRequestedFor(urlPathEqualTo("/chat/completions")));
        assertThat(topicRows(id)).isEmpty();

        rewrite("skill-profile.yaml", "aliases: [ Example field, Beispielthema ]", "aliases: [ Datenplattform ]");
        assertThat(config.reload()).isTrue();
        MODEL.resetRequests();

        var report = scoring.run();

        // Moved by exactly the bonus, the judged row carried over as it was stored, and not
        // one request to the model: an alias is a rule, and the judge was not asked about it.
        MODEL.verify(0, postRequestedFor(urlPathEqualTo("/chat/completions")));
        assertThat(scoreOf(id)).isEqualTo(before + 12);
        assertThat(factorsOf(id)).contains("role_fit", "interest_fit");
        assertThat(report.scored()).isZero();
        assertThat(jdbc.queryForObject("SELECT score_model FROM offer WHERE id = ?", String.class, id))
                .isEqualTo("test-model");

        // A rules version bump is the one change that makes the judge's answer stale.
        rewrite("matching-rules.yaml", "\nversion: 1", "\nversion: 2");
        assertThat(config.reload()).isTrue();
        scoring.run();
        MODEL.verify(1, postRequestedFor(urlPathEqualTo("/chat/completions")));
    }

    @Test
    void aTopicTheJudgeFindsIsWorthWhatAnAliasIsWorthAndNeverBoth() {
        answers("{\"reasons\":[{\"factor\":\"role_fit\",\"label\":\"not the target role\",\"points\":0}],"
                + "\"topics\":[\"Example topic\"]}");
        long plain = offer("Senior Java Entwickler (m/w/d)", "Java 21 und Spring Boot");
        long paraphrased = offer("Senior Java Entwickler (m/w/d)", "Java 21 und Spring Boot, sagt der Judge");
        long both = offer("Senior Java Entwickler (m/w/d)", "Java 21 und Spring Boot, ein Beispielthema");

        scoring.run();

        // The stub names the topic for all three, so `plain` shows what a judged match alone
        // is worth; `both` shows it is not added to the alias match but replaces nothing and
        // counts once, the alias row kept on a tie.
        assertThat(topicRows(paraphrased)).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("factor", "interest_judged");
            assertThat(row).containsEntry("points", 12);
        });
        assertThat(topicRows(both))
                .singleElement()
                .satisfies(row -> assertThat(row).containsEntry("factor", "interest_fit"));
        assertThat(scoreOf(both)).isEqualTo(scoreOf(paraphrased));
        assertThat(scoreOf(plain)).isEqualTo(scoreOf(paraphrased));
        MODEL.verify(postRequestedFor(urlPathEqualTo("/chat/completions"))
                .withRequestBody(containing("topic: Example topic")));
    }

    @Test
    void asksNoTopicQuestionWhenTheWeightTableHasNoRowForIt() throws IOException {
        rewrite("matching-rules.yaml", "    interest_fit: 15", "");
        rewrite("matching-rules.yaml", "    disinterest_fit: -20", "");
        assertThat(config.reload()).isTrue();
        answers("{\"reasons\":[{\"factor\":\"role_fit\",\"label\":\"not the target role\",\"points\":0}],"
                + "\"topics\":[\"Example topic\"]}");
        long id = offer("Senior Java Entwickler (m/w/d)", "Java 21 und Spring Boot, ein Beispielthema");

        scoring.run();

        MODEL.verify(postRequestedFor(urlPathEqualTo("/chat/completions"))
                .withRequestBody(notContaining("topic: Example topic")));
        assertThat(topicRows(id)).isEmpty();
    }

    @Test
    void aRetrievalVectorNeverMovesAScore() {
        // The vector narrows the topic filter and nothing else. Indexing an offer, then forcing
        // the rules half to be recomputed, leaves the total exactly where it was.
        long id = offer("Senior Java Entwickler (m/w/d)", "Java 21 und Spring Boot, Schwerpunkt Datenplattform");
        scoring.run();
        int before = scoreOf(id);

        float[] vector = new float[de.codeministry.leadgen.llm.Vectors.DIMENSIONS];
        vector[0] = 1f;
        jdbc.update(
                "UPDATE offer SET retrieval_embedding = CAST(? AS vector), retrieval_embedding_model = 'test-embed',"
                        + " retrieval_embedded_at = now(), profile_digest = NULL WHERE id = ?",
                de.codeministry.leadgen.llm.Vectors.literal(vector),
                id);
        scoring.run();

        assertThat(scoreOf(id)).isEqualTo(before);
    }

    @Test
    void anUnchangedProfileIsNotReTotalled() {
        long id = offer("Senior Java Entwickler (m/w/d)", "Java 21 und Spring Boot");
        scoring.run();
        var scoredAt = jdbc.queryForObject("SELECT scored_at FROM offer WHERE id = ?", Object.class, id);

        scoring.run();

        assertThat(jdbc.queryForObject("SELECT scored_at FROM offer WHERE id = ?", Object.class, id))
                .isEqualTo(scoredAt);
    }

    private int scoreOf(long id) {
        return jdbc.queryForObject("SELECT score_value FROM offer WHERE id = ?", Integer.class, id);
    }

    private List<Map<String, Object>> topicRows(long id) {
        return jdbc.queryForList(
                "SELECT factor, points, max_points, topic FROM offer_score_reason WHERE offer_id = ? AND topic IS NOT NULL",
                id);
    }

    private List<String> factorsOf(long id) {
        return jdbc.queryForList(
                "SELECT factor FROM offer_score_reason WHERE offer_id = ? ORDER BY position", String.class, id);
    }

    private void rewrite(String file, String from, String to) throws IOException {
        Path path = CONFIG.resolve(file);
        String content = Files.readString(path);
        assertThat(content).as("fixture must contain %s", from).contains(from);
        Files.writeString(path, content.replace(from, to));
    }

    private void answers(String content) {
        try {
            String body = JSON.writeValueAsString(Map.of(
                    "id",
                    "chatcmpl-test",
                    "object",
                    "chat.completion",
                    "created",
                    1,
                    "model",
                    "test-model",
                    "choices",
                    List.of(Map.of(
                            "index",
                            0,
                            "message",
                            Map.of("role", "assistant", "content", content),
                            "finish_reason",
                            "stop"))));
            MODEL.stubFor(post(urlPathEqualTo("/chat/completions"))
                    .willReturn(aResponse()
                            .withHeader("Content-Type", "application/json")
                            .withBody(body)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private long offer(String title, String description) {
        return jdbc.queryForObject("""
            INSERT INTO offer (source_id, external_id, title, description, url, fingerprint, status)
            VALUES (?, ?, ?, ?, 'https://example.invalid/x', 'fp', 'PASSED')
            RETURNING id
            """, Long.class, sourceId, "ext-" + System.nanoTime(), title, description);
    }

    /**
     * The shipped defaults with the llm block pointed at the stub, the same way
     * {@code ScoringWithAModelTest} does it and for the same reason: a placeholder left open
     * would be filled from whichever `.env` sits on the machine running the test.
     */
    private static Path configPointingAtTheStub() {
        try {
            Path dir = Files.createTempDirectory("leadgen-score-topics");
            dir.toFile().deleteOnExit();
            ConfigFixtures.materialize(dir);
            Path pipeline = dir.resolve("pipeline.yaml");
            String text = Files.readString(pipeline, StandardCharsets.UTF_8);
            text = set(text, "provider", "openai-compatible");
            text = set(text, "base_url", MODEL.baseUrl());
            text = set(text, "api_key", "test-key");
            text = set(text, "scoring", "test-model");
            text = set(text, "scoring_options", "other-model");
            Files.writeString(pipeline, text, StandardCharsets.UTF_8);
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String set(String yaml, String key, String value) {
        var matcher = java.util.regex.Pattern.compile("(?m)^([ \\t]*)" + java.util.regex.Pattern.quote(key) + ":.*$")
                .matcher(yaml);
        if (!matcher.find()) {
            throw new IllegalStateException("no `" + key + ":` in the shipped pipeline.yaml");
        }
        return matcher.replaceFirst("$1" + key + ": " + java.util.regex.Matcher.quoteReplacement(value));
    }
}
