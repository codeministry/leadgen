/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.score;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import de.codeministry.leadgen.config.ConfigFixtures;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
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
 * ISC-48: a score built from the configured weights, with a stated reason per factor.
 *
 * <p>The model is a stubbed OpenAI-compatible endpoint, and what is under test is that the
 * weight table decides and the model only contributes inside it. Whether a real model
 * judges <em>well</em> is an eval question, not a unit-test one.
 *
 * <p>The stub is started in a static initialiser rather than {@code @BeforeAll}, because
 * the configuration file that points at it is written by {@code @DynamicPropertySource},
 * which runs first. The alternative was reaching into the config registry with reflection
 * and rebuilding a record positionally — the kind of test setup that breaks silently the
 * next time a component is added.
 */
@SpringBootTest
@Testcontainers
class ScoringWithAModelTest {

    private static final WireMockServer MODEL;

    static {
        MODEL = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        MODEL.start();
    }

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private ScoringService scoring;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private de.codeministry.leadgen.config.ConfigRegistry config;

    private long sourceId;

    @DynamicPropertySource
    static void configuration(DynamicPropertyRegistry registry) {
        registry.add("leadgen.config-dir", () -> configPointingAtTheStub().toString());
    }

    @AfterAll
    static void stopModel() {
        MODEL.stop();
    }

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM offer_score_reason");
        jdbc.update("DELETE FROM offer");
        jdbc.update("DELETE FROM source");
        sourceId =
                jdbc.queryForObject("INSERT INTO source (name, kind) VALUES ('test', 'file') RETURNING id", Long.class);
        MODEL.resetAll();
    }

    @Test
    void scoresWithTheConfiguredWeightsAndStatesAReasonPerFactor() {
        answers(
                """
                {"reasons":[
                  {"factor":"role_fit","label":"backend engagement, the target role","points":15},
                  {"factor":"vague_description","label":"team size and scope are left open","points":-10}
                ]}
                """);
        long id = offer("Senior Java Entwickler (m/w/d)", "Java 21 und Spring Boot, 12 Monate");

        var report = scoring.run();

        assertThat(report.scored()).isEqualTo(1);
        assertThat(report.unscored()).isZero();

        Integer value = jdbc.queryForObject("SELECT score_value FROM offer WHERE id = ?", Integer.class, id);
        var reasons = jdbc.queryForList(
                "SELECT factor, points FROM offer_score_reason WHERE offer_id = ? ORDER BY position", id);

        assertThat(reasons)
                .extracting(r -> r.get("factor"))
                .contains("core_skill_overlap", "seniority_fit", "project_setup", "role_fit", "vague_description");
        int sum = reasons.stream().mapToInt(r -> (Integer) r.get("points")).sum();
        assertThat(value).isEqualTo(Math.max(0, Math.min(100, sum)));
        assertThat(jdbc.queryForObject("SELECT score_model FROM offer WHERE id = ?", String.class, id))
                .isEqualTo("test-model");
        assertThat(jdbc.queryForObject("SELECT score_band FROM offer WHERE id = ?", String.class, id))
                .isIn("SHORTLISTED", "REVIEW", "DISCARDED");
    }

    @Test
    void dropsAFactorTheModelInvented() {
        // The weight table decides, not the answer. A factor nobody asked about is an
        // answer to a different question.
        answers(
                """
                {"reasons":[
                  {"factor":"role_fit","label":"fits","points":15},
                  {"factor":"vibes","label":"feels right","points":40}
                ]}
                """);
        long id = offer("Senior Java Entwickler (m/w/d)", "Spring Boot");

        scoring.run();

        assertThat(factorsOf(id)).contains("role_fit").doesNotContain("vibes");
    }

    @Test
    void clampsAModelToWhatTheConfiguredWeightTableSays() {
        answers(
                """
                {"reasons":[{"factor":"role_fit","label":"perfect","points":900}]}
                """);
        long id = offer("Senior Java Entwickler (m/w/d)", "Spring Boot");

        scoring.run();

        // Read from the configuration the app was started with, not restated here. The four
        // bounds used to be Java constants that matched `scoring.weights` by coincidence: a
        // weight raised in the file moved the deterministic half of the score and left the
        // clamp where it was, and a test asserting the literal would have stayed green.
        int roleFit = config.snapshot().rules().scoring().weights().get("role_fit");
        assertThat(jdbc.queryForObject(
                        "SELECT points FROM offer_score_reason WHERE offer_id = ? AND factor = 'role_fit'",
                        Integer.class,
                        id))
                .isEqualTo(roleFit);
        assertThat(jdbc.queryForObject("SELECT score_value FROM offer WHERE id = ?", Integer.class, id))
                .isLessThanOrEqualTo(100);
    }

    @Test
    void keepsTheOfferWhenTheModelIsUnreachable() {
        // One endpoint having a bad afternoon must not end the run. The offer keeps what
        // the rules decided and scores lower, which is visible and reviewable.
        MODEL.stubFor(
                post(urlPathEqualTo("/chat/completions")).willReturn(aResponse().withStatus(503)));
        long id = offer("Senior Java Entwickler (m/w/d)", "Spring Boot");

        var report = scoring.run();

        assertThat(report.scored()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT score_value FROM offer WHERE id = ?", Integer.class, id))
                .isNotNull();
        assertThat(factorsOf(id)).contains("core_skill_overlap").doesNotContain("role_fit");
    }

    @Test
    void survivesAnAnswerThatIsNotJson() {
        MODEL.stubFor(post(urlPathEqualTo("/chat/completions"))
                .willReturn(aResponse().withBody("I'd rather write you a poem about Spring Boot.")));
        long id = offer("Senior Java Entwickler (m/w/d)", "Spring Boot");

        assertThat(scoring.run().scored()).isEqualTo(1);
        assertThat(factorsOf(id)).doesNotContain("role_fit");
    }

    /**
     * ISC-48: the model a run judges with is chosen per run, which is what makes comparing
     * two of them possible at all. The configured one stays the default.
     */
    @Test
    void judgesWithTheModelTheRunNames() {
        answers(
                """
                {"reasons":[{"factor":"role_fit","label":"backend engagement","points":15}]}
                """);
        long id = offer("Senior Java Entwickler (m/w/d)", "Java 21 und Spring Boot");

        assertThat(scoring.run("other-model").scored()).isEqualTo(1);

        assertThat(modelOf(id)).isEqualTo("other-model");
        MODEL.verify(postRequestedFor(urlPathEqualTo("/chat/completions"))
                .withRequestBody(matchingJsonPath("$.model", equalTo("other-model"))));
    }

    /**
     * The name arrives as a request parameter and the endpoint behind it is billed per
     * token, so the configured list is an allowlist: anything else is refused rather than
     * forwarded. A model the provider happens to accept would answer, score, and write
     * itself into `score_model`, where it is indistinguishable from a deliberate choice.
     */
    @Test
    void refusesAModelNobodyConfigured() {
        offer("Senior Java Entwickler (m/w/d)", "Java 21 und Spring Boot");

        assertThatThrownBy(() -> scoring.run("a-model-nobody-configured"))
                .isInstanceOf(Judges.UnknownModel.class)
                .hasMessageContaining("test-model")
                .hasMessageContaining("other-model");

        MODEL.verify(0, postRequestedFor(urlPathEqualTo("/chat/completions")));
    }

    /**
     * The run asks this before it reads a single source. Scoring is the last stage, so a
     * name checked only where it is used is checked after the whole pipeline has run — one
     * wasted pass, and the portals asked for nothing.
     */
    @Test
    void refusesAnUnknownModelWithoutBuildingAnything() {
        assertThatThrownBy(() -> scoring.checkModel("a-model-nobody-configured"))
                .isInstanceOf(Judges.UnknownModel.class);

        assertThatCode(() -> scoring.checkModel("other-model")).doesNotThrowAnyException();
        // Null is "whatever is configured", not a name, so it passes.
        assertThatCode(() -> scoring.checkModel(null)).doesNotThrowAnyException();
    }

    private String modelOf(long offerId) {
        return jdbc.queryForObject("SELECT score_model FROM offer WHERE id = ?", String.class, offerId);
    }

    private List<String> factorsOf(long offerId) {
        return jdbc.queryForList("SELECT factor FROM offer_score_reason WHERE offer_id = ?", String.class, offerId);
    }

    /**
     * A complete chat-completion envelope, not the one field the reader needs.
     *
     * <p>The provider SDK deserialises the whole object now, so a body that is merely enough
     * for us is rejected before the reader ever sees it — and the judge, which by contract
     * returns nothing rather than throwing, would report that as an offer with no judged
     * factors. Measured: three tests here failed with the judged half simply absent.
     */
    private void answers(String content) {
        try {
            String body = JSON.writeValueAsString(Map.of(
                    "id",
                    "chatcmpl-1",
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
        return jdbc.queryForObject(
                """
                INSERT INTO offer (source_id, external_id, title, description, url, fingerprint, status)
                VALUES (?, ?, ?, ?, 'https://example.invalid/x', 'fp', 'PASSED')
                RETURNING id
                """,
                Long.class,
                sourceId,
                "ext-" + System.nanoTime(),
                title,
                description);
    }

    /** The shipped defaults with the llm block pointed at the stub. No vendor is named. */
    private static Path configPointingAtTheStub() {
        try {
            Path dir = Files.createTempDirectory("leadgen-score-model");
            dir.toFile().deleteOnExit();
            ConfigFixtures.materialize(dir);

            Path pipeline = dir.resolve("pipeline.yaml");
            String text = Files.readString(pipeline, StandardCharsets.UTF_8)
                    .replace("provider: ${LLM_PROVIDER:}", "provider: openai-compatible")
                    .replace("base_url: ${LLM_BASE_URL:}", "base_url: " + MODEL.baseUrl())
                    .replace("api_key: ${LLM_API_KEY}", "api_key: test-key")
                    .replace("scoring:    ${LLM_MODEL_SCORING}", "scoring:    test-model")
                    // Named rather than left as a placeholder for the same reason the four
                    // above are: the resolver reads the developer's `.env`, so a list left
                    // open here would make the allowlist below depend on whose machine ran it.
                    .replace("scoring_options: ${LLM_MODEL_SCORING_OPTIONS:}", "scoring_options: other-model");
            Files.writeString(pipeline, text, StandardCharsets.UTF_8);
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
