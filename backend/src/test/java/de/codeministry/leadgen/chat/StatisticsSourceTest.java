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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.codeministry.leadgen.Databases;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * ISC-460, the server half: a {@code statistics} call reaches the browser as a {@code STATISTICS}
 * source built from the tool's own result — headline rows and the intake per day in the window —
 * and a reloaded conversation carries the same source, read from {@code chat_tool_call.data}. The
 * model's text states other digits; none of them may appear in the source.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class StatisticsSourceTest {

    private static final ModelStub MODEL = ModelStub.start();

    private static final Path CONFIG = MODEL.configuration();

    private static final ObjectMapper JSON = new ObjectMapper();

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = Databases.postgres();

    @DynamicPropertySource
    static void configuration(DynamicPropertyRegistry registry) {
        registry.add("leadgen.config-dir", CONFIG::toString);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @AfterAll
    static void stop() {
        MODEL.close();
    }

    @BeforeEach
    void reset() {
        MODEL.reset();
        jdbc.update("DELETE FROM chat_conversation");
        jdbc.update("DELETE FROM chat_call_budget");
        jdbc.update("DELETE FROM offer");
        jdbc.update("DELETE FROM source");
        long source =
                jdbc.queryForObject("INSERT INTO source (name, kind) VALUES ('test', 'file') RETURNING id", Long.class);
        // Thirty survivors, one every third day over ninety days: the analytics span then reaches past
        // both thirty-day windows, so neither is clipped and both hold days.
        jdbc.update("""
            INSERT INTO offer (source_id, external_id, title, url, fingerprint, status, score_value,
                               score_band, ingested_at)
            SELECT ?, 'o-' || g, 'Offer ' || g, 'https://example.invalid/o' || g, 'offer-' || g, 'PASSED',
                   40 + g * 2, CASE WHEN g % 3 = 0 THEN 'SHORTLISTED' ELSE 'REVIEW' END,
                   now() - make_interval(days => g * 3)
              FROM generate_series(0, 29) AS g
            """, source);
    }

    @Test
    void aStatisticsCallIsSentAndReloadedAsASourceBuiltFromTheToolsNumbers() throws Exception {
        JsonNode analytics = get("/api/v1/analytics");
        LocalDate to = LocalDate.parse(analytics.get("to").asText());
        LocalDate from = to.minusDays(29);
        LocalDate compareTo = from.minusDays(1);
        LocalDate compareFrom = compareTo.minusDays(29);
        MODEL.enqueue(ModelStub.toolCalls(
                "statistics",
                "{\"from\":\"" + from + "\",\"to\":\"" + to + "\",\"compareFrom\":\"" + compareFrom
                        + "\",\"compareTo\":\"" + compareTo + "\"}"));
        MODEL.enqueue(ModelStub.text(Duration.ZERO, "There were 987654 offers, up by 123456."));
        long conversation =
                jdbc.queryForObject("INSERT INTO chat_conversation (title) VALUES ('') RETURNING id", Long.class);

        List<TurnStream.Event> events = TurnStream.ask(port, conversation, "How did intake change?");

        assertThat(TurnStream.names(events)).containsSubsequence("turn", "step", "step", "sources", "done");
        JsonNode sources = JSON.readTree(events.stream()
                .filter(event -> event.name().equals("sources"))
                .findFirst()
                .orElseThrow()
                .data());
        JsonNode card = only(sources.get("sources"));
        assertThat(card.get("kind").asText()).isEqualTo("STATISTICS");
        assertThat(card.get("ordinal").asInt()).isEqualTo(1);
        assertThat(card.get("from").asText()).isEqualTo(from.toString());
        assertThat(card.get("to").asText()).isEqualTo(to.toString());
        assertThat(card.get("compareFrom").asText()).isEqualTo(compareFrom.toString());
        assertThat(card.get("compareTo").asText()).isEqualTo(compareTo.toString());

        // The series is the endpoint's intake per day inside the window, primaries per day.
        List<JsonNode> expectedSeries = new ArrayList<>();
        int intake = 0;
        int compared = 0;
        for (JsonNode day : analytics.get("intake").get("byIngestedAt")) {
            LocalDate on = LocalDate.parse(day.get("day").asText());
            int primaries = day.get("primaries").asInt();
            if (!on.isBefore(from) && !on.isAfter(to)) {
                expectedSeries.add(
                        JSON.createObjectNode().put("day", on.toString()).put("count", primaries));
                intake += primaries;
            } else if (!on.isBefore(compareFrom) && !on.isAfter(compareTo)) {
                compared += primaries;
            }
        }
        assertThat(intake).isPositive();
        assertThat(compared).isPositive();
        assertThat(card.get("series")).isEqualTo(JSON.valueToTree(expectedSeries));

        // At most eight rows, the intake with its comparison and the server's own difference.
        JsonNode rows = card.get("rows");
        assertThat(rows.size()).isBetween(1, 8);
        JsonNode offersIn = row(rows, "Offers in");
        assertThat(offersIn.get("value").asInt()).isEqualTo(intake);
        assertThat(offersIn.get("compareValue").asInt()).isEqualTo(compared);
        assertThat(offersIn.get("delta").asInt()).isEqualTo(intake - compared);
        assertThat(row(rows, "Passed the filter").get("value").asInt())
                .isEqualTo(analytics.get("funnel").get("survived").asInt());
        // Nothing the model wrote reached the card.
        assertThat(card.toString()).doesNotContain("987654", "123456");

        // Stored on the tool call, and the reloaded turn carries the very same source.
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM chat_tool_call WHERE tool = 'statistics' AND data IS NOT NULL",
                        Integer.class))
                .isEqualTo(1);
        JsonNode reloaded = get("/api/v1/chat/conversations/" + conversation);
        assertThat(reloaded.get("turns").get(0).get("sources")).isEqualTo(sources.get("sources"));
    }

    private static JsonNode only(JsonNode array) {
        assertThat(array).hasSize(1);
        return array.get(0);
    }

    private static JsonNode row(JsonNode rows, String label) {
        for (JsonNode row : rows) {
            if (row.get("label").asText().equals(label)) {
                return row;
            }
        }
        throw new AssertionError("no row labelled " + label + " in " + rows);
    }

    private JsonNode get(String path) throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as(path).isEqualTo(200);
            return JSON.readTree(response.body());
        }
    }
}
