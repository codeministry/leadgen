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
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
 * What a spent day's allowance does to a scoring run.
 *
 * <p>The limit is written into the materialised configuration once, before the context
 * starts, rather than rewritten per test: a {@code @DynamicPropertySource} supplier may run
 * more than once, and one that creates a directory hands the loader a different one than the
 * test writes into.
 */
@SpringBootTest
@Testcontainers
class ScoringStopsAtTheBudgetTest {

    private static final WireMockServer MODEL;

    static {
        MODEL = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        MODEL.start();
    }

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = Databases.postgres();

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private ScoringService scoring;

    @Autowired
    private JdbcTemplate jdbc;

    private long sourceId;

    @DynamicPropertySource
    static void configuration(DynamicPropertyRegistry registry) {
        registry.add("leadgen.config-dir", CONFIG::toString);
    }

    private static final Path CONFIG = configWithOneCallADay();

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM offer_score_reason");
        jdbc.update("DELETE FROM offer");
        jdbc.update("DELETE FROM source");
        jdbc.update("DELETE FROM llm_call_budget");
        sourceId =
                jdbc.queryForObject("INSERT INTO source (name, kind) VALUES ('test', 'file') RETURNING id", Long.class);
        MODEL.resetAll();
        answers();
    }

    @Test
    void judgesUpToTheAllowanceAndLeavesTheRestDue() {
        // One call a day, two offers due. What is not judged must not be written as judged:
        // a total from four of five weights is not comparable to one from all five, and the
        // offer that did not get an answer has to come back tomorrow.
        offer("Senior Java Entwickler (m/w/d)");
        offer("Java Backend Entwickler (m/w/d)");

        var report = scoring.run();

        assertThat(report.scored()).isEqualTo(1);
        MODEL.verify(1, postRequestedFor(urlPathEqualTo("/chat/completions")));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM offer WHERE score_value IS NULL", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT calls FROM llm_call_budget WHERE day = current_date", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void asksNothingAtAllOnceTheDayIsSpent() {
        jdbc.update("INSERT INTO llm_call_budget (day, calls) VALUES (current_date, 1)");
        offer("Senior Java Entwickler (m/w/d)");

        var report = scoring.run();

        assertThat(report.scored()).isZero();
        MODEL.verify(0, postRequestedFor(urlPathEqualTo("/chat/completions")));
        // Unscored rather than scored-with-nothing, which is what leaves it due tomorrow.
        assertThat(jdbc.queryForObject("SELECT score_value FROM offer", Integer.class))
                .isNull();
    }

    private void offer(String title) {
        jdbc.update(
                """
                INSERT INTO offer (source_id, external_id, title, description, url, fingerprint, status)
                VALUES (?, ?, ?, 'Java 21 und Spring Boot, 12 Monate', 'https://example.invalid/x', ?, 'PASSED')
                """,
                sourceId,
                "ext-" + System.nanoTime(),
                title,
                title.toLowerCase());
    }

    private void answers() {
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
                            Map.of(
                                    "role",
                                    "assistant",
                                    "content",
                                    "{\"reasons\":[{\"factor\":\"role_fit\",\"label\":\"backend\",\"points\":15}]}"),
                            "finish_reason",
                            "stop"))));
            MODEL.stubFor(post(urlPathEqualTo("/chat/completions"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody(body)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path configWithOneCallADay() {
        try {
            Path dir = Files.createTempDirectory("leadgen-score-budget");
            dir.toFile().deleteOnExit();
            ConfigFixtures.materialize(dir);

            Path pipeline = dir.resolve("pipeline.yaml");
            String text = Files.readString(pipeline, StandardCharsets.UTF_8);
            text = set(text, "provider", "openai-compatible");
            text = set(text, "base_url", MODEL.baseUrl());
            text = set(text, "api_key", "test-key");
            text = set(text, "scoring", "test-model");
            text = set(text, "max_calls_per_day", "1");
            Files.writeString(pipeline, text, StandardCharsets.UTF_8);
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

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
