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
import java.time.Duration;
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
 * ISC-429 end to end: the stub model first calls the offer search, which returns one offer, and
 * then answers citing that offer, an offer no tool returned and a knocked-out offer. Only the
 * first reaches the reader as a link; the other two arrive as unverified text.
 *
 * <p>The markers are split across chunks on purpose, because that is how a model streams them and
 * the filter has to hold a half marker back rather than let {@code [[off} through as text.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ChatGroundingTest {

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

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

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
    void onlyAnIdATurnsToolReturnedBecomesALink() {
        long returned = offer("Java Developer", "PASSED");
        long neverReturned = offer("Gardener", "PASSED");
        long knockedOut = offer("Java Developer abroad", "REJECTED");
        MODEL.enqueue(ModelStub.toolCalls("search_offers", "{\"text\":\"java\"}"));
        MODEL.enqueue(ModelStub.text(
                Duration.ofMillis(10),
                "Take the role [[off",
                "er:" + returned + "]]. Skip [[offer:" + neverReturned,
                "]] and [[offer:" + knockedOut + "]]."));

        List<TurnStream.Event> events = TurnStream.ask(port, conversation(), "Which Java roles?");

        // The premise: the tool returned the first offer and neither of the others.
        assertThat(jdbc.queryForList(
                        "SELECT (r ->> 'id')::bigint FROM chat_tool_call, jsonb_array_elements(returned_ids) r",
                        Long.class))
                .containsExactly(returned);

        String answer = TurnStream.text(events);
        assertThat(answer)
                .isEqualTo("Take the role [1](cite:offer/" + returned + "). Skip ⟨unverified:" + neverReturned
                        + "⟩ and ⟨unverified:" + knockedOut + "⟩.");
        assertThat(answer).doesNotContain("cite:offer/" + neverReturned, "cite:offer/" + knockedOut, "[[");

        List<String> names = TurnStream.names(events);
        assertThat(names).containsSubsequence("turn", "step", "step", "text", "sources", "done");
        String sources = events.stream()
                .filter(event -> event.name().equals("sources"))
                .findFirst()
                .orElseThrow()
                .data();
        assertThat(sources).contains("\"id\":" + returned).doesNotContain("\"id\":" + neverReturned);
        assertThat(jdbc.queryForObject("SELECT answer_md FROM chat_turn", String.class))
                .isEqualTo(answer);
    }

    /**
     * ISC-454: pins widen what the tools read and nothing else. A knocked-out offer pinned second
     * (so the old single-pin mirror does not reach it) is a link, because the turn's pinned lookup
     * returned it; an offer outside the pinned view that no tool returned stays unverified although
     * it is on the working list; a knocked-out offer nobody pinned stays unverified; the offer the
     * view-bound search returned is a link.
     */
    @Test
    void aPinIsCitableAndNothingOutsideWhatAToolReturnedIs() {
        long pinnedFirst = offer("Kotlin Developer", "PASSED");
        long pinnedKnockedOut = offer("Java Developer abroad", "REJECTED");
        long inView = offer("Java Developer", "PASSED");
        long outsideView = offer("Gardener", "PASSED");
        long unpinnedKnockedOut = offer("Gardener abroad", "REJECTED");
        long conversation = conversations.create(
                null,
                List.of(
                        new ChatContextItem(ChatContextKind.OFFER, pinnedFirst, null, null, null),
                        new ChatContextItem(ChatContextKind.OFFER, pinnedKnockedOut, null, null, null),
                        new ChatContextItem(ChatContextKind.SHORTLIST_VIEW, null, "q=java", null, null)));
        // The model asks for gardeners; the pinned view answers with the Java offer instead.
        MODEL.enqueue(ModelStub.toolCalls("search_offers", "{\"text\":\"gardener\"}"));
        MODEL.enqueue(ModelStub.text(
                Duration.ofMillis(5),
                "Pinned [[offer:" + pinnedKnockedOut + "]], outside [[offer:" + outsideView + "]], unpinned [[offer:"
                        + unpinnedKnockedOut + "]], returned [[offer:" + inView + "]]."));

        String answer = TurnStream.text(TurnStream.ask(port, conversation, "Which ones?"));

        assertThat(answer).contains("(cite:offer/" + pinnedKnockedOut + ")");
        assertThat(answer).contains("(cite:offer/" + inView + ")");
        assertThat(answer).contains("⟨unverified:" + outsideView + "⟩", "⟨unverified:" + unpinnedKnockedOut + "⟩");
        assertThat(answer).doesNotContain("cite:offer/" + outsideView, "cite:offer/" + unpinnedKnockedOut);
    }

    private long offer(String title, String status) {
        return jdbc.queryForObject(
                """
                INSERT INTO offer (source_id, external_id, title, description, url, fingerprint, status,
                                   filter_stage, score_value, portal, ingested_at)
                VALUES (?, ?, ?, 'Replacing a monolith.', ?, ?, ?, ?, 80, 'portal-a', now())
                RETURNING id
                """,
                Long.class,
                sourceId,
                "ext-" + title,
                title,
                "https://example.invalid/" + title.hashCode(),
                title.toLowerCase(),
                status,
                status.equals("REJECTED") ? "ABROAD" : null);
    }

    private long conversation() {
        return jdbc.queryForObject("INSERT INTO chat_conversation (title) VALUES ('') RETURNING id", Long.class);
    }
}
