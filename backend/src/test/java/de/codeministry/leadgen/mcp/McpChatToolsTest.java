/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import de.codeministry.leadgen.Databases;
import de.codeministry.leadgen.chat.ChatBudget;
import de.codeministry.leadgen.chat.tools.ApplicationTool;
import de.codeministry.leadgen.chat.tools.ProfileTool;
import de.codeministry.leadgen.chat.tools.SemanticSearchTool;
import de.codeministry.leadgen.chat.tools.StatisticsTool;
import de.codeministry.leadgen.config.ConfigFixtures;
import de.codeministry.leadgen.llm.LlmBudget;
import de.codeministry.leadgen.llm.Vectors;
import de.codeministry.leadgen.retrieval.QueryEmbedder;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The four chat tools served over MCP answer what the chat's own tools answer, without a chat turn
 * (spec 023, ISC-484).
 *
 * <p>Each MCP answer is compared, as JSON, with the chat tool's answer to the same arguments,
 * serialised by the application's own mapper. Around the MCP calls nothing of the chat moves: no
 * conversation, turn, tool call or pinned context is written, and the chat's daily ceiling is not
 * touched. The one model request, the query's embedding, is paid from {@code llm.budget}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class McpChatToolsTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = Databases.postgres();

    private static final String EMBEDDING_MODEL = "test-embed";

    private static final Path CONFIG = retrievalConfig();

    private static final List<String> CHAT_TABLES =
            List.of("chat_conversation", "chat_turn", "chat_tool_call", "chat_context", "chat_call_budget");

    @DynamicPropertySource
    static void configuration(DynamicPropertyRegistry registry) {
        registry.add("leadgen.config-dir", CONFIG::toString);
    }

    /** The query's vector, decided by the test; the stub pays through whichever ceiling it is handed. */
    @MockitoBean
    private QueryEmbedder queries;

    @Autowired
    private SemanticSearchTool semantic;

    @Autowired
    private StatisticsTool statistics;

    @Autowired
    private ApplicationTool application;

    @Autowired
    private ProfileTool profile;

    @Autowired
    private LlmBudget llmBudget;

    @Autowired
    private ChatBudget chatBudget;

    @Autowired
    private JsonMapper json;

    @Autowired
    private JdbcTemplate jdbc;

    @LocalServerPort
    private int port;

    private long sourceId;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM application_event");
        jdbc.update("DELETE FROM application");
        jdbc.update("DELETE FROM offer_score_reason");
        jdbc.update("DELETE FROM offer");
        jdbc.update("DELETE FROM source");
        sourceId =
                jdbc.queryForObject("INSERT INTO source (name, kind) VALUES ('test', 'file') RETURNING id", Long.class);
        when(queries.vectorFor(eq("event streaming"), eq(EMBEDDING_MODEL), any()))
                .thenAnswer(call -> ((BooleanSupplier) call.getArgument(2)).getAsBoolean()
                        ? Optional.of(Vectors.literal(direction(0)))
                        : Optional.empty());
    }

    @Test
    void theFourAnswerAsTheChatsToolsDo() {
        long near = vectored("Kafka platform", direction(1));
        vectored("Kafka archive", direction(2));
        jdbc.update("INSERT INTO application (offer_id, status, sent_on) VALUES (?, 'SENT', DATE '2026-09-10')", near);
        String from = LocalDate.now().minusDays(7).toString();
        String to = LocalDate.now().toString();
        var client = McpTestClient.connect("http://localhost:" + port);

        var answers = new LinkedHashMap<String, JsonNode>();
        answers.put("semantic", client.callToolJson("leadgen_semantic_search", "{\"query\":\"event streaming\"}"));
        answers.put("statistics", client.callToolJson("leadgen_statistics", "{}"));
        answers.put(
                "statistics-window",
                client.callToolJson("leadgen_statistics", "{\"from\":\"%s\",\"to\":\"%s\"}".formatted(from, to)));
        answers.put("application", client.callToolJson("leadgen_application", "{\"offerId\":%d}".formatted(near)));
        answers.put("profile", client.callToolJson("leadgen_profile", "{}"));

        assertThat(answers.get("semantic").path("offers")).hasSize(2);
        assertThat(answers.get("semantic")).isEqualTo(tree(semantic.searchByMeaning("event streaming")));
        assertThat(answers.get("statistics")).isEqualTo(tree(statistics.statistics(null, null, null, null)));
        assertThat(answers.get("statistics-window")).isEqualTo(tree(statistics.statistics(from, to, null, null)));
        assertThat(answers.get("application")).isEqualTo(tree(application.application(near)));
        assertThat(answers.get("profile")).isEqualTo(tree(profile.profile()));
    }

    @Test
    void noneOfThemTouchesTheChat() {
        long near = vectored("Kafka platform", direction(1));
        jdbc.update("INSERT INTO application (offer_id, status) VALUES (?, 'SENT')", near);
        var client = McpTestClient.connect("http://localhost:" + port);
        Map<String, Long> before = chatRows();
        int chatBefore = chatBudget.used();
        int llmBefore = llmBudget.used();

        client.callToolJson("leadgen_semantic_search", "{\"query\":\"event streaming\"}");
        client.callToolJson("leadgen_statistics", "{}");
        client.callToolJson("leadgen_application", "{\"offerId\":%d}".formatted(near));
        client.callToolJson("leadgen_profile", "{}");

        assertThat(chatRows()).as("no conversation, turn, tool call or pin").isEqualTo(before);
        assertThat(chatBudget.used()).as("the chat's day").isEqualTo(chatBefore);
        assertThat(llmBudget.used()).as("the one embedding, from llm.budget").isEqualTo(llmBefore + 1);
    }

    private Map<String, Long> chatRows() {
        var rows = new LinkedHashMap<String, Long>();
        for (String table : CHAT_TABLES) {
            rows.put(table, jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class));
        }
        return rows;
    }

    private JsonNode tree(Object value) {
        return json.readTree(json.writeValueAsString(value));
    }

    private long vectored(String title, float[] vector) {
        long id = jdbc.queryForObject(
                """
                INSERT INTO offer (source_id, external_id, title, url, fingerprint, status, score_value, portal)
                VALUES (?, ?, ?, ?, ?, 'PASSED', 60, 'portal-a')
                RETURNING id
                """,
                Long.class,
                sourceId,
                "vec-" + title,
                title,
                "https://example.invalid/vec/" + title.replace(' ', '-'),
                title.toLowerCase());
        jdbc.update(
                "UPDATE offer SET retrieval_embedding = CAST(? AS vector), retrieval_embedding_model = ?,"
                        + " retrieval_embedded_at = now() WHERE id = ?",
                Vectors.literal(vector),
                EMBEDDING_MODEL,
                id);
        return id;
    }

    /** A unit vector in the first plane, {@code step} two-thousandths of a quarter turn from the axis. */
    private static float[] direction(int step) {
        float[] vector = new float[Vectors.DIMENSIONS];
        double angle = step * Math.PI / 2000;
        vector[0] = (float) Math.cos(angle);
        vector[1] = (float) Math.sin(angle);
        return vector;
    }

    /** The shipped files with retrieval switched on over a test embedding model. */
    private static Path retrievalConfig() {
        try {
            Path dir = Files.createTempDirectory("leadgen-mcp-chat-tools");
            dir.toFile().deleteOnExit();
            ConfigFixtures.materialize(dir);
            Path pipeline = dir.resolve("pipeline.yaml");
            String text = Files.readString(pipeline, StandardCharsets.UTF_8)
                    .replaceFirst("(?m)^(\\s*)provider:.*$", "$1provider: openai-compatible")
                    .replaceFirst("(?m)^(\\s*)base_url:.*$", "$1base_url: http://localhost:1/v1")
                    .replaceFirst("(?m)^(\\s*)api_key:.*$", "$1api_key: test-key")
                    .replaceFirst("(?m)^(\\s*)embedding:.*$", "$1embedding: " + EMBEDDING_MODEL)
                    .replace("${RETRIEVAL_ENABLED:false}", "true");
            Files.writeString(pipeline, text, StandardCharsets.UTF_8);
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
