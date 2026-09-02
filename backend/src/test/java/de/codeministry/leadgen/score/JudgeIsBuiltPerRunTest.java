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
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import de.codeministry.leadgen.config.ConfigFixtures;
import de.codeministry.leadgen.config.ConfigRegistry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * The judge is a parameter of the run, not a bean fixed at startup.
 *
 * <p>Nothing else pins this, and it is a hard requirement: the configuration is
 * hot-reloadable, so a key added to `.env` at five in the afternoon has to start producing
 * scores without a restart. Built once at startup instead, the same file edit would appear
 * to do nothing at all — the value visibly present, the shortlist still unscored, and no
 * error anywhere to explain it.
 *
 * <p>The walk is deliberately the real one: start with no model configured, prove the offer
 * comes back unscored, point the configuration at a stub, reload, and score the same offer.
 * The second half also proves the staleness rule, because an offer that was never scored is
 * due again by definition.
 */
@SpringBootTest
@Testcontainers
class JudgeIsBuiltPerRunTest {

    private static final WireMockServer MODEL;
    /** Written before the context starts and rewritten mid-test; hence a static field. */
    private static final Path CONFIG_DIR;

    static {
        MODEL = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        MODEL.start();
        CONFIG_DIR = configWithNoModelAtAll();
    }

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private ScoringService scoring;

    @Autowired
    private ConfigRegistry config;

    @Autowired
    private JdbcTemplate jdbc;

    private long sourceId;

    /**
     * Memoized through the static above rather than computed here: this supplier is called
     * once per property resolution, not once per context, and a second temp directory would
     * be the one the reload later rewrites while the application reads the first.
     */
    @DynamicPropertySource
    static void configuration(DynamicPropertyRegistry registry) {
        registry.add("leadgen.config-dir", CONFIG_DIR::toString);
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
    void startsScoringAfterAModelIsConfigured_withoutARestart() {
        long id = offer("Senior Java Entwickler (m/w/d)", "Java 21 und Spring Boot, 12 Monate");

        // Nothing configured to answer: the deterministic reasons are written and the total
        // is withheld, because a number from five of nine weights is not comparable to one
        // from all nine.
        var withoutAModel = scoring.run();

        assertThat(withoutAModel.unscored()).isEqualTo(1);
        assertThat(scoreOf(id)).isNull();
        assertThat(reasonsOf(id)).isPositive();

        pointConfigAtTheStub();
        answers(
                """
                {"reasons":[{"factor":"role_fit","label":"backend engagement, the target role","points":15}]}
                """);

        assertThat(config.reload()).isTrue();
        assertThat(config.snapshot().application().llm().models().scoring()).isEqualTo("test-model");
        var withAModel = scoring.run();

        assertThat(withAModel.scored()).isEqualTo(1);
        assertThat(scoreOf(id)).isNotNull();
        assertThat(modelOf(id)).isEqualTo("test-model");
    }

    private void answers(String json) {
        MODEL.stubFor(post(urlPathEqualTo("/chat/completions"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody(JSON.createObjectNode()
                                .putPOJO(
                                        "choices",
                                        JSON.createArrayNode()
                                                .add(JSON.createObjectNode()
                                                        .putPOJO(
                                                                "message",
                                                                JSON.createObjectNode()
                                                                        .put("content", json))))
                                .toString())));
    }

    private Integer scoreOf(long id) {
        return jdbc.queryForObject("SELECT score_value FROM offer WHERE id = ?", Integer.class, id);
    }

    private String modelOf(long id) {
        return jdbc.queryForObject("SELECT score_model FROM offer WHERE id = ?", String.class, id);
    }

    private int reasonsOf(long id) {
        return jdbc.queryForObject("SELECT count(*) FROM offer_score_reason WHERE offer_id = ?", Integer.class, id);
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

    /**
     * The shipped defaults with every `${LLM_*}` emptied.
     *
     * <p>Emptied rather than left as placeholders on purpose, and for the same reason
     * `ScoringWithoutAModelTest` does it: the resolver reads the developer's own `.env`, so a
     * test about the keyless path would start scoring against a real endpoint on the one
     * machine where the configuration is finished.
     */
    private static Path configWithNoModelAtAll() {
        try {
            Path dir = Files.createTempDirectory("leadgen-judge-per-run");
            dir.toFile().deleteOnExit();
            ConfigFixtures.materialize(dir);
            Path pipeline = dir.resolve("pipeline.yaml");
            Files.writeString(
                    pipeline,
                    Files.readString(pipeline, StandardCharsets.UTF_8)
                            .replaceAll("(?m)^(\\s*)api_key:.*$", "$1api_key:")
                            .replaceAll("(?m)^(\\s*)scoring:\\s+\\$\\{LLM_MODEL_SCORING\\}.*$", "$1scoring:")
                            .replaceAll("(?m)^(\\s*)scoring_options:.*$", "$1scoring_options:"),
                    StandardCharsets.UTF_8);
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The same directory, now naming the stub. No vendor appears anywhere. */
    private static void pointConfigAtTheStub() {
        try {
            Path pipeline = CONFIG_DIR.resolve("pipeline.yaml");
            Files.writeString(
                    pipeline,
                    Files.readString(pipeline, StandardCharsets.UTF_8)
                            .replaceAll("(?m)^(\\s*)provider:.*$", "$1provider: openai-compatible")
                            .replaceAll("(?m)^(\\s*)base_url:.*$", "$1base_url: " + MODEL.baseUrl())
                            .replaceAll("(?m)^(\\s*)api_key:.*$", "$1api_key: test-key")
                            .replaceAll("(?m)^(\\s*)scoring:\\s*$", "$1scoring: test-model"),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
