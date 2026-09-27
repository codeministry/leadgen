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
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * ISC-452 and ISC-453 end to end: what a conversation's context does to the tools of a turn.
 *
 * <p>A pinned shortlist view is the screen's list, whatever the model asks for: the stub model calls
 * {@code search_offers} with arguments that contradict each pinned view, and the ids and the
 * {@code matched} the model is handed are the ones {@code GET /api/v1/offers?<view>} answers. A
 * pinned analytics window is the window {@code statistics} answers for, whatever window the model
 * names. A conversation holds at most ten offers, and the turn's pinned lookup reads every one.
 *
 * <p>What the model saw is read from the stub's own request log — the tool responses of the second
 * request — rather than from the tool bean, so the check covers the turn handing its context over.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class ChatContextTest {

    private static final ModelStub MODEL = ModelStub.start();

    /** Created once, never inside the property supplier — see {@code LlmBudgetTest}. */
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

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private ConversationRepository conversations;

    private long sourceId;

    @AfterAll
    static void stop() {
        MODEL.close();
    }

    @BeforeEach
    void reset() {
        MODEL.reset();
        jdbc.update("DELETE FROM chat_conversation");
        jdbc.update("DELETE FROM chat_call_budget");
        jdbc.update("DELETE FROM application");
        jdbc.update("DELETE FROM offer_score_reason");
        jdbc.update("DELETE FROM offer");
        jdbc.update("DELETE FROM source");
        sourceId =
                jdbc.queryForObject("INSERT INTO source (name, kind) VALUES ('test', 'file') RETURNING id", Long.class);
    }

    @Test
    void aPinnedViewWinsOverTheModelsArgumentsForEachOfThreeViews() throws Exception {
        corpus();
        List<String> views =
                List.of("q=java&sort=fresh", "portal=portal-b&sort=score-asc", "minScore=50&maxScore=80&sort=score");
        long conversation = conversations.create(
                null,
                views.stream()
                        .map(view -> new ChatContextItem(ChatContextKind.SHORTLIST_VIEW, null, view, null, null))
                        .toList());
        // Each call contradicts the view it names: other words, another portal, another range.
        MODEL.enqueue(ModelStub.toolCalls(
                "search_offers", "{\"text\":\"gardener\",\"view\":1}",
                "search_offers", "{\"portals\":[\"portal-a\"],\"sort\":\"score\",\"view\":2}",
                "search_offers", "{\"minScore\":90,\"view\":3}"));
        MODEL.enqueue(ModelStub.text(Duration.ofMillis(5), "Done."));

        List<TurnStream.Event> events = TurnStream.ask(port, conversation, "Which offers?");
        assertThat(TurnStream.names(events).getLast()).isEqualTo("done");

        List<JsonNode> answered = toolResponses("search_offers");
        assertThat(answered).hasSize(3);
        for (int i = 0; i < views.size(); i++) {
            JsonNode screen = shortlist(views.get(i));
            List<Long> screenIds = new ArrayList<>();
            screen.path("entries")
                    .forEach(entry ->
                            screenIds.add(entry.path("offer").path("id").asLong()));
            // The premise: every view lists something, so equal lists are not two empty ones.
            assertThat(screenIds).as("view %s", views.get(i)).isNotEmpty();
            assertThat(ids(answered.get(i))).as("view %s", views.get(i)).containsExactlyElementsOf(screenIds);
            assertThat(answered.get(i).path("matched").asLong())
                    .as("view %s", views.get(i))
                    .isEqualTo(screen.path("matched").asLong());
        }
        // And the ledger holds what the model was handed, per call.
        List<String> ledger = jdbc.queryForList(
                "SELECT returned_ids::text FROM chat_tool_call WHERE tool = 'search_offers' ORDER BY ordinal",
                String.class);
        assertThat(ledger).hasSize(3);
        for (int i = 0; i < 3; i++) {
            for (long id : ids(answered.get(i))) {
                assertThat(ledger.get(i)).contains(String.valueOf(id));
            }
        }
    }

    @Test
    void aPinnedWindowWinsOverTheWindowTheModelAsksFor() throws Exception {
        corpus();
        long conversation = conversations.create(
                null,
                List.of(new ChatContextItem(
                        ChatContextKind.ANALYTICS_WINDOW,
                        null,
                        null,
                        LocalDate.parse("2026-09-01"),
                        LocalDate.parse("2026-09-10"))));
        MODEL.enqueue(ModelStub.toolCalls("statistics", "{\"from\":\"2026-08-01\",\"to\":\"2026-09-30\"}"));
        MODEL.enqueue(ModelStub.text(Duration.ofMillis(5), "Done."));

        TurnStream.ask(port, conversation, "How many came in?");

        List<JsonNode> answered = toolResponses("statistics");
        assertThat(answered).hasSize(1);
        JsonNode result = answered.getFirst();
        assertThat(result.path("from").asText()).isEqualTo("2026-09-01");
        assertThat(result.path("to").asText()).isEqualTo("2026-09-10");
        List<String> days = new ArrayList<>();
        result.path("intake").forEach(day -> days.add(day.path("day").asText()));
        // The premise: the pinned window holds days, and none of the model's wider window leaks in.
        assertThat(days).isNotEmpty().allSatisfy(day -> assertThat(day).isBetween("2026-09-01", "2026-09-10"));
    }

    @Test
    void anEleventhPinnedOfferIsRefusedWithTheLimitsReasonOnCreateAndOnReplace() {
        List<Long> offers = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            offers.add(offer("Offer " + i, "PASSED", 70, "portal-a", "2026-09-05"));
        }
        String eleven = context(offers);
        String ten = context(offers.subList(0, 10));

        MvcTestResult refused = mvc.post()
                .uri("/api/v1/chat/conversations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(eleven)
                .exchange();
        assertThat(refused).hasStatus(400);
        assertThat(refused.getResponse().getErrorMessage()).contains("at most 10 pinned offers");

        MvcTestResult created = mvc.post()
                .uri("/api/v1/chat/conversations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(ten)
                .exchange();
        assertThat(created).hasStatus(201);
        long id = jdbc.queryForObject("SELECT id FROM chat_conversation", Long.class);

        MvcTestResult replaced = mvc.put()
                .uri("/api/v1/chat/conversations/" + id + "/context")
                .contentType(MediaType.APPLICATION_JSON)
                .content(eleven)
                .exchange();
        assertThat(replaced).hasStatus(400);
        assertThat(replaced.getResponse().getErrorMessage()).contains("at most 10 pinned offers");
        // The refused replacement left the stored ten as they were.
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM chat_context WHERE conversation_id = ? AND kind = 'OFFER'",
                        Integer.class,
                        id))
                .isEqualTo(10);
    }

    @Test
    void theTurnsPinnedLookupReturnsEveryPinnedOffer() {
        long first = offer("Java Developer", "PASSED", 80, "portal-a", "2026-09-05");
        long knockedOut = offer("Java Developer abroad", "REJECTED", 80, "portal-a", "2026-09-06");
        long third = offer("Kotlin Developer", "PASSED", 70, "portal-b", "2026-09-07");
        long conversation = conversations.create(
                null,
                List.of(
                        new ChatContextItem(ChatContextKind.OFFER, first, null, null, null),
                        new ChatContextItem(ChatContextKind.OFFER, knockedOut, null, null, null),
                        new ChatContextItem(ChatContextKind.OFFER, third, null, null, null)));
        MODEL.enqueue(ModelStub.text(Duration.ofMillis(5), "Read."));

        TurnStream.ask(port, conversation, "Compare them");

        assertThat(jdbc.queryForList("""
                        SELECT (r ->> 'id')::bigint FROM chat_tool_call, jsonb_array_elements(returned_ids) r
                        WHERE tool = 'pinned_offer'
                        """, Long.class)).containsExactly(first, knockedOut, third);
    }

    /** Five offers on three portals, three score levels and four days, so each view differs from the others. */
    private void corpus() {
        offer("Java Developer", "PASSED", 85, "portal-a", "2026-09-05");
        offer("Java Architect", "PASSED", 60, "portal-b", "2026-09-06");
        offer("Kotlin Developer", "PASSED", 75, "portal-b", "2026-08-20");
        offer("Gardener", "PASSED", 40, "portal-a", "2026-09-20");
        offer("Java Tester", "PASSED", 55, "portal-a", "2026-09-07");
    }

    private JsonNode shortlist(String view) throws Exception {
        MvcTestResult result = mvc.get().uri("/api/v1/offers?" + view).exchange();
        assertThat(result).hasStatusOk();
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    /** The results of one tool as the model was handed them, read from the stub's last request. */
    private List<JsonNode> toolResponses(String tool) throws Exception {
        List<String> bodies = MODEL.bodies();
        JsonNode request = JSON.readTree(bodies.getLast());
        List<JsonNode> results = new ArrayList<>();
        // The assistant message names the calls; the tool messages answer them in the same order.
        List<String> names = new ArrayList<>();
        for (JsonNode message : request.path("messages")) {
            for (JsonNode call : message.path("tool_calls")) {
                names.add(call.path("function").path("name").asText());
            }
        }
        int answered = 0;
        for (JsonNode message : request.path("messages")) {
            if (message.path("role").asText().equals("tool")) {
                String name = message.has("name") ? message.path("name").asText() : names.get(answered);
                answered++;
                if (name.equals(tool)) {
                    results.add(JSON.readTree(message.path("content").asText()));
                }
            }
        }
        return results;
    }

    private static List<Long> ids(JsonNode result) {
        List<Long> ids = new ArrayList<>();
        result.path("offers").forEach(offer -> ids.add(offer.path("id").asLong()));
        return ids;
    }

    private static String context(List<Long> offers) {
        return offers.stream()
                .map(id -> "{\"kind\":\"OFFER\",\"offerId\":" + id + "}")
                .collect(Collectors.joining(",", "{\"context\":[", "]}"));
    }

    private long offer(String title, String status, int score, String portal, String day) {
        return jdbc.queryForObject(
                """
                INSERT INTO offer (source_id, external_id, title, description, url, fingerprint, status,
                                   filter_stage, score_value, portal, ingested_at)
                VALUES (?, ?, ?, 'Replacing a monolith.', ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """,
                Long.class,
                sourceId,
                "ext-" + title,
                title,
                "https://example.invalid/" + title.hashCode(),
                title.toLowerCase(),
                status,
                status.equals("REJECTED") ? "ABROAD" : null,
                score,
                portal,
                java.sql.Timestamp.from(LocalDate.parse(day)
                        .atTime(12, 0)
                        .atZone(ZoneId.systemDefault())
                        .toInstant()));
    }
}
