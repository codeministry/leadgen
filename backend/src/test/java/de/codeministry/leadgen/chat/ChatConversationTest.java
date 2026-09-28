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
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * ISC-431, the server half: conversations are kept, listed newest first, deleted one by one, and
 * an id that names nothing is a 404 the drawer turns into an empty state.
 *
 * <p>ISC-430, the server half: a finished turn keeps the tool calls it made and the ids they
 * returned, and reopening the conversation lists each cited row once as a source.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class ChatConversationTest {

    private static final ModelStub MODEL = ModelStub.start();

    /** Created once, never inside the property supplier — see {@code LlmBudgetTest}. */
    private static final Path CONFIG = MODEL.configuration();

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = Databases.postgres();

    @DynamicPropertySource
    static void configuration(DynamicPropertyRegistry registry) {
        registry.add("leadgen.config-dir", CONFIG::toString);
    }

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @LocalServerPort
    private int port;

    @AfterAll
    static void stop() {
        MODEL.close();
    }

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM chat_conversation");
        MODEL.reset();
    }

    @Test
    void listsNewestFirstAndDeletesOneByOne() {
        long first = create();
        long middle = create();
        long last = create();

        assertThat(mvc.delete().uri("/api/v1/chat/conversations/{id}", middle)).hasStatus(204);

        assertThat(mvc.get().uri("/api/v1/chat/conversations"))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$[*].id")
                .isEqualTo(List.of((int) last, (int) first));
    }

    @Test
    void opensAConversationWithItsPinnedOffer() {
        long id = create();

        assertThat(mvc.get().uri("/api/v1/chat/conversations/{id}", id))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.turns")
                .asArray()
                .isEmpty();
    }

    @Test
    void anIdThatNamesNothingIsNotFound() {
        assertThat(mvc.get().uri("/api/v1/chat/conversations/{id}", 999_999)).hasStatus(404);
        assertThat(mvc.delete().uri("/api/v1/chat/conversations/{id}", 999_999)).hasStatus(404);
    }

    @Test
    void aFinishedTurnKeepsItsToolCallsAndListsEachSourceOnce() {
        jdbc.update("DELETE FROM application");
        jdbc.update("DELETE FROM offer");
        jdbc.update("DELETE FROM source");
        long source =
                jdbc.queryForObject("INSERT INTO source (name, kind) VALUES ('test', 'file') RETURNING id", Long.class);
        long first = offer(source, "Java Developer A");
        long second = offer(source, "Java Developer B");
        long applied = jdbc.queryForObject(
                "INSERT INTO application (offer_id, status) VALUES (?, 'SENT') RETURNING id", Long.class, second);
        MODEL.enqueue(ModelStub.toolCalls(
                "search_offers", "{\"text\":\"java\"}", "application", "{\"offerId\":" + second + "}"));
        MODEL.enqueue(ModelStub.text(
                java.time.Duration.ZERO,
                "A [[offer:" + first + "]], B [[offer:" + second + "]], applied [[application:" + applied
                        + "]], and again [[offer:" + first + "]]."));
        long conversation = create();

        List<TurnStream.Event> events = TurnStream.ask(port, conversation, "Where do I stand?");
        assertThat(TurnStream.names(events).getLast()).isEqualTo("done");

        // Both calls stored, each with the ids it returned.
        List<Map<String, Object>> calls =
                jdbc.queryForList("SELECT tool, returned_ids::text AS returned FROM chat_tool_call ORDER BY ordinal");
        assertThat(calls).extracting(call -> call.get("tool")).containsExactly("search_offers", "application");
        assertThat((String) calls.get(0).get("returned"))
                .contains("\"id\": " + first)
                .contains("\"id\": " + second);
        assertThat((String) calls.get(1).get("returned"))
                .contains("{\"id\": " + applied + ", \"kind\": \"APPLICATION\"}")
                .contains("{\"id\": " + second + ", \"kind\": \"OFFER\"}");

        // Reloaded: the steps, and each cited row once, numbered as the answer numbered it.
        var turn = assertThat(mvc.get().uri("/api/v1/chat/conversations/{id}", conversation))
                .hasStatusOk()
                .bodyJson();
        turn.extractingPath("$.turns[0].steps[*].tool").isEqualTo(List.of("search_offers", "application"));
        turn.extractingPath("$.turns[0].sources[*].n").isEqualTo(List.of(1, 2, 3));
        turn.extractingPath("$.turns[0].sources[*].kind").isEqualTo(List.of("OFFER", "OFFER", "APPLICATION"));
        turn.extractingPath("$.turns[0].sources[*].id").isEqualTo(List.of((int) first, (int) second, (int) applied));
        turn.extractingPath("$.turns[0].sources[2].title").isEqualTo("Java Developer B");
        turn.extractingPath("$.turns[0].answer")
                .isEqualTo("A [1](cite:offer/" + first + "), B [2](cite:offer/" + second
                        + "), applied [3](cite:application/" + applied + "), and again [1](cite:offer/" + first + ").");
    }

    private long offer(long source, String title) {
        return jdbc.queryForObject(
                """
                INSERT INTO offer (source_id, external_id, title, description, url, fingerprint, status,
                                   score_value, portal, ingested_at)
                VALUES (?, ?, ?, 'Replacing a monolith.', ?, ?, 'PASSED', 80, 'portal-a', now())
                RETURNING id
                """,
                Long.class,
                source,
                "ext-" + title,
                title,
                "https://example.invalid/" + title.hashCode(),
                title.toLowerCase());
    }

    /** ISC-440, the server half: the same question again as a new turn, the replaced one kept. */
    @Test
    void regenerateAsksTheLastQuestionAgainAndKeepsTheReplacedAnswer() throws Exception {
        long conversation = create();
        MODEL.enqueue(ModelStub.text(java.time.Duration.ZERO, "First answer."));
        TurnStream.ask(port, conversation, "Which roles?");
        long first = jdbc.queryForObject("SELECT id FROM chat_turn", Long.class);
        MODEL.enqueue(ModelStub.text(java.time.Duration.ZERO, "Second ", "answer."));

        List<TurnStream.Event> events = TurnStream.post(
                port,
                "/api/v1/chat/conversations/" + conversation + "/turns/" + first + "/regenerate",
                "",
                event -> {});

        assertThat(TurnStream.names(events).getLast()).isEqualTo("done");
        assertThat(TurnStream.text(events)).isEqualTo("Second answer.");
        List<Map<String, Object>> turns =
                jdbc.queryForList("SELECT id, question, answer_md, replaces_turn_id FROM chat_turn ORDER BY ordinal");
        assertThat(turns).hasSize(2);
        assertThat(turns.get(0)).containsEntry("answer_md", "First answer.").containsEntry("replaces_turn_id", null);
        assertThat(turns.get(1))
                .containsEntry("question", "Which roles?")
                .containsEntry("answer_md", "Second answer.")
                .containsEntry("replaces_turn_id", first);
        // The model was not shown its replaced attempt as settled dialogue.
        assertThat(MODEL.bodies().getLast()).doesNotContain("First answer.");

        var shown = mvc.get().uri("/api/v1/chat/conversations/" + conversation).exchange();
        assertThat(shown).hasStatusOk();
        assertThat(shown).bodyJson().extractingPath("$.turns[1].replacesTurnId").isEqualTo((int) first);
        assertThat(shown).bodyJson().extractingPath("$.turns[1].answer").isEqualTo("Second answer.");
        assertThat(TurnStream.postStatus(
                        port, "/api/v1/chat/conversations/" + conversation + "/turns/999999/regenerate"))
                .isEqualTo(404);
    }

    /**
     * ISC-446, the server half: the offer a conversation is created with is stored on it, and the
     * conversation hands it back when it is opened again — which is what a reload does.
     */
    @Test
    void aCreateWithAPinStoresItAndTheReloadReturnsIt() {
        long offer = offer(freshSource(), "Pinned Java Developer");

        var created = mvc.post()
                .uri("/api/v1/chat/conversations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"pinnedOfferId\":" + offer + "}")
                .exchange();

        assertThat(created).hasStatus(201);
        assertThat(created).bodyJson().extractingPath("$.pinnedOfferId").isEqualTo((int) offer);
        long id = jdbc.queryForObject("SELECT max(id) FROM chat_conversation", Long.class);
        assertThat(jdbc.queryForObject("SELECT pinned_offer_id FROM chat_conversation WHERE id = ?", Long.class, id))
                .isEqualTo(offer);
        assertThat(mvc.get().uri("/api/v1/chat/conversations/{id}", id))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.pinnedOfferId")
                .isEqualTo((int) offer);
    }

    /**
     * ISC-446 and the seam of ISC-449: a create that names its offer in the context list pins it the
     * same as {@code pinnedOfferId} does, which is still accepted for one release.
     */
    @Test
    void aCreateWithAnOfferInItsContextStoresThePin() {
        long offer = offer(freshSource(), "Context Java Developer");

        var created = mvc.post()
                .uri("/api/v1/chat/conversations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"context\":[{\"kind\":\"OFFER\",\"offerId\":" + offer + "}]}")
                .exchange();

        assertThat(created).hasStatus(201);
        assertThat(created).bodyJson().extractingPath("$.pinnedOfferId").isEqualTo((int) offer);
    }

    /**
     * ISC-451, the server half: a conversation created with a shortlist view, an analytics window
     * and an offer keeps them in that order and with their kinds; a PUT replaces the whole list, and
     * the reload and the conversation list both read the replacement back. The first offer of the
     * list is still the {@code pinnedOfferId} of the view, for one release.
     */
    @Test
    void aContextIsStoredOnCreateReplacedByAPutAndReadBackInOrder() {
        long source = freshSource();
        long first = offer(source, "Context Kotlin Developer");
        long second = offer(source, "Context Angular Developer");

        var created = mvc.post()
                .uri("/api/v1/chat/conversations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"context\":[{\"kind\":\"SHORTLIST_VIEW\",\"query\":\"tag=java&sort=score\"},"
                        + "{\"kind\":\"ANALYTICS_WINDOW\",\"from\":\"2026-09-01\",\"to\":\"2026-09-27\"},"
                        + "{\"kind\":\"OFFER\",\"offerId\":" + first + "}]}")
                .exchange();

        assertThat(created).hasStatus(201);
        var body = assertThat(created).bodyJson();
        body.extractingPath("$.context.length()").isEqualTo(3);
        body.extractingPath("$.context[0].kind").isEqualTo("SHORTLIST_VIEW");
        body.extractingPath("$.context[0].query").isEqualTo("tag=java&sort=score");
        body.extractingPath("$.context[1].kind").isEqualTo("ANALYTICS_WINDOW");
        body.extractingPath("$.context[1].from").isEqualTo("2026-09-01");
        body.extractingPath("$.context[1].to").isEqualTo("2026-09-27");
        body.extractingPath("$.context[2].kind").isEqualTo("OFFER");
        body.extractingPath("$.context[2].offerId").isEqualTo((int) first);
        body.extractingPath("$.pinnedOfferId").isEqualTo((int) first);
        long id = jdbc.queryForObject("SELECT max(id) FROM chat_conversation", Long.class);
        assertThat(jdbc.queryForList(
                        "SELECT kind FROM chat_context WHERE conversation_id = ? ORDER BY ordinal", String.class, id))
                .containsExactly("SHORTLIST_VIEW", "ANALYTICS_WINDOW", "OFFER");

        // Replaced whole: the view goes, the second offer leads, the window moves behind it.
        var replaced = mvc.put()
                .uri("/api/v1/chat/conversations/{id}/context", id)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"context\":[{\"kind\":\"OFFER\",\"offerId\":" + second + "},"
                        + "{\"kind\":\"ANALYTICS_WINDOW\",\"from\":\"2026-08-01\",\"to\":\"2026-08-31\"},"
                        + "{\"kind\":\"OFFER\",\"offerId\":" + first + "}]}")
                .exchange();

        assertThat(replaced).hasStatusOk();
        for (var result : List.of(
                replaced, mvc.get().uri("/api/v1/chat/conversations/{id}", id).exchange())) {
            var view = assertThat(result).bodyJson();
            view.extractingPath("$.context.length()").isEqualTo(3);
            view.extractingPath("$.context[0].kind").isEqualTo("OFFER");
            view.extractingPath("$.context[0].offerId").isEqualTo((int) second);
            view.extractingPath("$.context[1].kind").isEqualTo("ANALYTICS_WINDOW");
            view.extractingPath("$.context[1].from").isEqualTo("2026-08-01");
            view.extractingPath("$.context[1].to").isEqualTo("2026-08-31");
            view.extractingPath("$.context[2].offerId").isEqualTo((int) first);
            view.extractingPath("$.pinnedOfferId").isEqualTo((int) second);
        }
        var listed = assertThat(mvc.get().uri("/api/v1/chat/conversations")).bodyJson();
        listed.extractingPath("$[0].id").isEqualTo((int) id);
        listed.extractingPath("$[0].context.length()").isEqualTo(3);
        listed.extractingPath("$[0].context[0].offerId").isEqualTo((int) second);
        listed.extractingPath("$[0].context[1].kind").isEqualTo("ANALYTICS_WINDOW");
        assertThat(jdbc.queryForList(
                        "SELECT ordinal FROM chat_context WHERE conversation_id = ? ORDER BY ordinal",
                        Integer.class,
                        id))
                .containsExactly(1, 2, 3);

        // An empty list unpins everything.
        var cleared = mvc.put()
                .uri("/api/v1/chat/conversations/{id}/context", id)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"context\":[]}")
                .exchange();
        assertThat(cleared).hasStatusOk();
        assertThat(cleared).bodyJson().extractingPath("$.context.length()").isEqualTo(0);
        assertThat(cleared).bodyJson().extractingPath("$.pinnedOfferId").isNull();
    }

    /** A context put on a conversation that does not exist is its 404; one naming a missing offer is the offer's. */
    @Test
    void aContextPutOnAnUnknownConversationOrOfferIsNotFound() {
        long id = create();

        assertThat(mvc.put()
                        .uri("/api/v1/chat/conversations/987654321/context")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"context\":[]}"))
                .hasStatus(404);
        assertThat(mvc.put()
                        .uri("/api/v1/chat/conversations/{id}/context", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"context\":[{\"kind\":\"OFFER\",\"offerId\":987654321}]}"))
                .hasStatus(404);
        assertThat(mvc.put()
                        .uri("/api/v1/chat/conversations/{id}/context", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"context\":[{\"kind\":\"ANALYTICS_WINDOW\",\"from\":\"2026-09-27\"}]}"))
                .hasStatus(400);
    }

    /**
     * ISC-449, the seam: a row of the list carries its last activity, and the title it shows is the
     * one the conversation was renamed to when it was, the derived one otherwise.
     */
    @Test
    void theListShowsTheEffectiveTitleAndTheLastActivity() {
        long renamed = create();
        long derived = create();
        jdbc.update("UPDATE chat_conversation SET title = 'Derived', custom_title = 'Mine' WHERE id = ?", renamed);
        jdbc.update("UPDATE chat_conversation SET title = 'Derived only' WHERE id = ?", derived);
        String updatedAt = jdbc.queryForObject(
                "SELECT to_char(updated_at AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI') FROM chat_conversation"
                        + " WHERE id = ?",
                String.class,
                renamed);

        var list = assertThat(mvc.get().uri("/api/v1/chat/conversations"))
                .hasStatusOk()
                .bodyJson();
        list.extractingPath("$[*].title").isEqualTo(List.of("Derived only", "Mine"));
        list.extractingPath("$[1].lastActivityAt").asString().startsWith(updatedAt);
        assertThat(mvc.get().uri("/api/v1/chat/conversations/{id}", renamed))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.title")
                .isEqualTo("Mine");
    }

    /**
     * ISC-448, the server half: deleting a conversation whose turn is streaming stops that turn
     * first. The stop is what closes the model's connection, and the turn ends {@code STOPPED} —
     * its terminal write lands before the rows go, which is only possible if the delete waited for
     * it. Without the stop the turn streams all twenty chunks into rows that no longer exist.
     */
    @Test
    void deletingAConversationStopsItsStreamingTurnFirst() {
        String[] parts = new String[20];
        for (int i = 0; i < parts.length; i++) {
            parts[i] = "c" + i + " ";
        }
        MODEL.enqueue(ModelStub.text(java.time.Duration.ofMillis(100), parts));
        long conversation = create();
        int[] texts = new int[1];
        int[] deleteStatus = new int[1];
        int[] textsAtDelete = new int[1];
        java.time.Instant[] deletedAt = new java.time.Instant[1];

        List<TurnStream.Event> events = TurnStream.post(
                port, "/api/v1/chat/conversations/" + conversation + "/turns", "{\"question\":\"Twenty?\"}", event -> {
                    if (event.name().equals("text") && ++texts[0] == 3) {
                        deleteStatus[0] = deleteStatus(conversation);
                        deletedAt[0] = java.time.Instant.now();
                        textsAtDelete[0] = texts[0];
                    }
                });

        assertThat(deleteStatus[0]).isEqualTo(204);
        assertThat(texts[0]).as("text events after the delete").isEqualTo(textsAtDelete[0]);
        assertThat(events.getLast().name()).isEqualTo("done");
        assertThat(TurnStream.JsonText.field(events.getLast().data(), "state")).isEqualTo("STOPPED");
        java.time.Instant deadline = deletedAt[0].plus(java.time.Duration.ofSeconds(1));
        while (MODEL.brokenAt() == null && java.time.Instant.now().isBefore(deadline)) {
            Thread.onSpinWait();
        }
        assertThat(MODEL.brokenAt()).as("the stub saw its connection closed").isNotNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_turn", Integer.class))
                .isZero();
        assertThat(mvc.get().uri("/api/v1/chat/conversations/{id}", conversation))
                .hasStatus(404);
    }

    /**
     * ISC-449, the server half: a rename is stored trimmed and at most 120 characters, an emptied
     * one clears the name so the derived title shows again, and each is what a reload reads.
     */
    @Test
    void aRenameIsTrimmedCappedAndAnEmptyOneBringsTheDerivedTitleBack() {
        long id = create();
        jdbc.update("UPDATE chat_conversation SET title = 'Derived' WHERE id = ?", id);

        assertThat(rename(id, "   Padded name  "))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.title")
                .isEqualTo("Padded name");
        assertReloadedTitle(id, "Padded name");
        assertThat(jdbc.queryForObject("SELECT custom_title FROM chat_conversation WHERE id = ?", String.class, id))
                .isEqualTo("Padded name");

        String long200 = "x".repeat(200);
        assertThat(rename(id, long200))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.title")
                .isEqualTo("x".repeat(120));
        assertReloadedTitle(id, "x".repeat(120));

        assertThat(rename(id, ""))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.title")
                .isEqualTo("Derived");
        assertReloadedTitle(id, "Derived");
        assertThat(jdbc.queryForObject("SELECT custom_title FROM chat_conversation WHERE id = ?", String.class, id))
                .isNull();

        assertThat(rename(999_999, "Nobody")).hasStatus(404);
    }

    private org.springframework.test.web.servlet.assertj.MvcTestResult rename(long id, String title) {
        return mvc.patch()
                .uri("/api/v1/chat/conversations/{id}", id)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"" + title + "\"}")
                .exchange();
    }

    /**
     * ISC-473: a turn that ends incomplete keeps the reason its live {@code error} event named, so a
     * reload can say it too; every other ending, and a turn stored before the column, names none.
     * The reload also carries when the turn finished, which the status popover's duration reads.
     */
    @Test
    void anIncompleteTurnKeepsItsReasonAndEveryOtherEndingNamesNone() {
        try {
            MODEL.enqueue(ModelStub.brokenText(java.time.Duration.ZERO, "Half "));
            assertEnded(TurnStream.ask(port, create(), "Model?"), "MODEL", "INCOMPLETE", "MODEL");

            // The shipped ceiling is six rounds; the seventh request for tools ends the turn.
            for (int round = 0; round < 7; round++) {
                MODEL.enqueue(ModelStub.toolCalls("profile", "{}"));
            }
            assertEnded(TurnStream.ask(port, create(), "Rounds?"), "ROUNDS", "INCOMPLETE", "ROUNDS");

            // Today's allowance spent before the first call: refused without asking the model.
            jdbc.update("INSERT INTO chat_call_budget (day, calls) VALUES (current_date, 1000000)"
                    + " ON CONFLICT (day) DO UPDATE SET calls = excluded.calls");
            assertEnded(TurnStream.ask(port, create(), "Budget?"), "BUDGET", "INCOMPLETE", "BUDGET");
            jdbc.update("DELETE FROM chat_call_budget");

            MODEL.enqueue(ModelStub.text(java.time.Duration.ZERO, "Done."));
            assertEnded(TurnStream.ask(port, create(), "Done?"), null, "DONE", null);

            String[] parts = new String[20];
            java.util.Arrays.fill(parts, "c ");
            MODEL.enqueue(ModelStub.text(java.time.Duration.ofMillis(100), parts));
            long stopped = create();
            long[] turn = new long[1];
            int[] texts = new int[1];
            List<TurnStream.Event> events = TurnStream.post(
                    port, "/api/v1/chat/conversations/" + stopped + "/turns", "{\"question\":\"Stop?\"}", event -> {
                        if (event.name().equals("turn")) {
                            turn[0] = Long.parseLong(TurnStream.JsonText.field(event.data(), "turnId"));
                        }
                        if (event.name().equals("text") && ++texts[0] == 2) {
                            stop(stopped, turn[0]);
                        }
                    });
            assertEnded(events, null, "STOPPED", null);

            // Stored before V36: incomplete, and no reason on record.
            long old = create();
            jdbc.update("""
                    INSERT INTO chat_turn (conversation_id, ordinal, question, answer_md, state, finished_at)
                    VALUES (?, 1, 'Old?', 'Old half.', 'INCOMPLETE', now())
                    """, old);
            var reloaded = assertThat(mvc.get().uri("/api/v1/chat/conversations/{id}", old))
                    .hasStatusOk()
                    .bodyJson();
            reloaded.extractingPath("$.turns[0].state").isEqualTo("INCOMPLETE");
            reloaded.extractingPath("$.turns[0].endReason").isNull();
        } finally {
            jdbc.update("DELETE FROM chat_call_budget");
        }
    }

    /**
     * ISC-477, over real HTTP: three of five conversations deleted in one request while one of the
     * three is streaming — its turn is stopped and its model connection closed before the rows
     * go, the other two stay, and an unknown id among them changes nothing.
     */
    @Test
    void aBulkDeleteTakesExactlyTheNamedConversationsAndStopsTheOneStreaming() {
        String[] parts = new String[20];
        java.util.Arrays.fill(parts, "c ");
        MODEL.enqueue(ModelStub.text(java.time.Duration.ofMillis(100), parts));
        long streaming = create();
        long second = create();
        long third = create();
        long keptA = create();
        long keptB = create();
        int[] texts = new int[1];
        String[] answer = new String[1];

        List<TurnStream.Event> events = TurnStream.post(
                port, "/api/v1/chat/conversations/" + streaming + "/turns", "{\"question\":\"Twenty?\"}", event -> {
                    if (event.name().equals("text") && ++texts[0] == 3) {
                        answer[0] = bulkDelete("{\"ids\":[" + streaming + "," + second + "," + third + ",987654321]}");
                    }
                });

        assertThat(answer[0])
                .contains("\"deleted\"")
                .contains(String.valueOf(streaming))
                .doesNotContain("987654321");
        assertThat(events.getLast().name()).isEqualTo("done");
        assertThat(TurnStream.JsonText.field(events.getLast().data(), "state")).isEqualTo("STOPPED");
        java.time.Instant deadline = java.time.Instant.now().plus(java.time.Duration.ofSeconds(1));
        while (MODEL.brokenAt() == null && java.time.Instant.now().isBefore(deadline)) {
            Thread.onSpinWait();
        }
        assertThat(MODEL.brokenAt()).as("the stub saw its connection closed").isNotNull();
        assertThat(jdbc.queryForList("SELECT id FROM chat_conversation ORDER BY id", Long.class))
                .containsExactly(keptA, keptB);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_turn", Integer.class))
                .isZero();
    }

    /** A real HTTP bulk delete, so it can run while a turn's stream is being read on this thread. */
    private String bulkDelete(String body) {
        try (var client = java.net.http.HttpClient.newHttpClient()) {
            var response = client.send(
                    java.net.http.HttpRequest.newBuilder(java.net.URI.create(
                                    "http://localhost:" + port + "/api/v1/chat/conversations/bulk-delete"))
                            .header("Content-Type", "application/json")
                            .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
                            .build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            return response.body();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** The live ending, the stored column, and the reload of the conversation the turn is in. */
    private void assertEnded(List<TurnStream.Event> events, String liveReason, String state, String stored) {
        TurnStream.Event last = events.getLast();
        if (liveReason == null) {
            assertThat(last.name()).isEqualTo("done");
        } else {
            assertThat(last.name()).isEqualTo("error");
            assertThat(TurnStream.JsonText.field(last.data(), "reason")).isEqualTo(liveReason);
        }
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT conversation_id, state, end_reason, finished_at FROM chat_turn ORDER BY id DESC LIMIT 1");
        assertThat(row).containsEntry("state", state).containsEntry("end_reason", stored);
        assertThat(row.get("finished_at")).as("finished_at").isNotNull();
        var reloaded = assertThat(mvc.get().uri("/api/v1/chat/conversations/{id}", row.get("conversation_id")))
                .hasStatusOk()
                .bodyJson();
        reloaded.extractingPath("$.turns[0].state").isEqualTo(state);
        if (stored == null) {
            reloaded.extractingPath("$.turns[0].endReason").isNull();
        } else {
            reloaded.extractingPath("$.turns[0].endReason").isEqualTo(stored);
        }
        reloaded.extractingPath("$.turns[0].finishedAt").isNotNull();
    }

    /** A real HTTP stop, so it can run while the turn's stream is being read on this thread. */
    private void stop(long conversation, long turn) {
        try (var client = java.net.http.HttpClient.newHttpClient()) {
            client.send(
                    java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:" + port
                                    + "/api/v1/chat/conversations/" + conversation + "/turns/" + turn + "/stop"))
                            .POST(java.net.http.HttpRequest.BodyPublishers.noBody())
                            .build(),
                    java.net.http.HttpResponse.BodyHandlers.discarding());
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private void assertReloadedTitle(long id, String expected) {
        assertThat(mvc.get().uri("/api/v1/chat/conversations/{id}", id))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.title")
                .isEqualTo(expected);
    }

    /** A real HTTP delete, so it can run while the turn's stream is being read on this thread. */
    private int deleteStatus(long conversation) {
        try (var client = java.net.http.HttpClient.newHttpClient()) {
            return client.send(
                            java.net.http.HttpRequest.newBuilder(java.net.URI.create(
                                            "http://localhost:" + port + "/api/v1/chat/conversations/" + conversation))
                                    .DELETE()
                                    .build(),
                            java.net.http.HttpResponse.BodyHandlers.discarding())
                    .statusCode();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** One source with nothing under it: every offer and application before it is gone. */
    private long freshSource() {
        jdbc.update("DELETE FROM application");
        jdbc.update("DELETE FROM offer");
        jdbc.update("DELETE FROM source");
        return jdbc.queryForObject("INSERT INTO source (name, kind) VALUES ('test', 'file') RETURNING id", Long.class);
    }

    /** A pin on an offer that does not exist is the offer's 404, not the foreign key's 500. */
    @Test
    void aPinOnAnOfferThatDoesNotExistIsNotFound() {
        var result = mvc.post()
                .uri("/api/v1/chat/conversations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"pinnedOfferId\":987654321}")
                .exchange();

        assertThat(result).hasStatus(404);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_conversation", Integer.class))
                .isZero();
    }

    private long create() {
        var result = mvc.post()
                .uri("/api/v1/chat/conversations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
                .exchange();
        assertThat(result).hasStatus(201);
        return jdbc.queryForObject("SELECT max(id) FROM chat_conversation", Long.class);
    }
}
