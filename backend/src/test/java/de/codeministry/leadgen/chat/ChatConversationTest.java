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
