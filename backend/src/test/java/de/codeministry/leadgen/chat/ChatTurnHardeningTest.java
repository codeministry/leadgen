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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockingDetails;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.codeministry.leadgen.Databases;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The turn's edges the happy-path tests do not reach: a link the model copies out of its own
 * history, a pinned offer, a repository call that throws halfway, a turn a dead process left
 * streaming, a regenerate in the middle of a conversation, and how often a long answer is written.
 *
 * <p>The repository is a spy so a test can make one of its calls throw and count the others; every
 * call still reaches the real database.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ChatTurnHardeningTest {

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

    @MockitoSpyBean
    private ConversationRepository conversations;

    @Autowired
    private ChatTurnService turns;

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

    /**
     * Finding 2: an earlier answer goes back to the model with its links turned back into markers,
     * and a resolved link the model writes itself — copied, or planted by an advert — reaches the
     * reader as unverified text, even for an offer that is real and reachable.
     */
    @Test
    void theModelNeverSeesNorMintsAResolvedLink() {
        long offer = offer("Kotlin Developer");
        long conversation = conversation(null);
        pastTurn(conversation, 1, "Which roles?", "Take [1](cite:offer/" + offer + ") and not ⟨unverified:77⟩.");
        MODEL.enqueue(ModelStub.text(Duration.ZERO, "Still [1](ci", "te:offer/" + offer + ")."));

        List<TurnStream.Event> events = TurnStream.ask(port, conversation, "And now?");

        assertThat(MODEL.bodies().getFirst())
                .contains("Take [[offer:" + offer + "]] and not 77.")
                .doesNotContain("cite:offer", "unverified");
        assertThat(TurnStream.text(events))
                .isEqualTo("Still ⟨unverified:" + offer + "⟩.")
                .doesNotContain("cite:");
        assertThat(TurnStream.names(events).getLast()).isEqualTo("done");
    }

    /**
     * Finding 3: a pinned conversation's turn starts with that offer's lookup, recorded like a tool
     * call, so the model reads the offer and a citation of it is a link.
     */
    @Test
    void aPinnedOfferIsLookedUpFirstAndCitable() {
        long offer = offer("Pinned Platform Engineer");
        long conversation = conversation(offer);
        MODEL.enqueue(ModelStub.text(Duration.ZERO, "This one [[offer:" + offer + "]] fits."));

        List<TurnStream.Event> events = TurnStream.ask(port, conversation, "Is it a fit?");

        assertThat(MODEL.bodies().getFirst()).contains("Pinned Platform Engineer", "Replacing a monolith.");
        assertThat(TurnStream.names(events)).containsSubsequence("turn", "step", "step", "text", "sources", "done");
        assertThat(jdbc.queryForList(
                        "SELECT (r ->> 'id')::bigint FROM chat_tool_call, jsonb_array_elements(returned_ids) r",
                        Long.class))
                .containsExactly(offer);
        assertThat(TurnStream.text(events)).isEqualTo("This one [1](cite:offer/" + offer + ") fits.");
    }

    /** Finding 4: a repository call that throws halfway still ends the turn, stored and streamed. */
    @Test
    void aThrowingRepositoryCallEndsTheTurnIncompleteWithAnError() {
        long conversation = conversation(null);
        doThrow(new IllegalStateException("the database went away"))
                .when(conversations)
                .history(anyLong(), anyLong());

        List<TurnStream.Event> events = TurnStream.ask(port, conversation, "Anything?");

        assertThat(TurnStream.names(events)).containsExactly("turn", "error");
        assertThat(TurnStream.JsonText.field(events.getLast().data(), "reason")).isEqualTo("MODEL");
        assertThat(jdbc.queryForObject("SELECT state FROM chat_turn", String.class))
                .isEqualTo("INCOMPLETE");
    }

    /**
     * Fix 8: the database failing mid-stream is not the model stopping. The turn ends with one
     * error that says what failed, and nothing retries the write that just threw.
     */
    @Test
    void aDatabaseFailureMidStreamIsNotReportedAsTheModelAndIsNotWrittenAgain() {
        long conversation = conversation(null);
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("the database went away"))
                .when(conversations)
                .append(anyLong(), org.mockito.ArgumentMatchers.anyString());
        // A pause past the flush interval, so the text is written while the model is still streaming.
        MODEL.enqueue(ModelStub.text(ChatTurnService.FLUSH_INTERVAL.plusMillis(100), "Half ", "an ", "answer."));

        List<TurnStream.Event> events = TurnStream.ask(port, conversation, "Anything?");

        long appends = mockingDetails(conversations).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("append"))
                .count();
        assertThat(appends).as("append calls after the one that threw").isEqualTo(1);
        assertThat(TurnStream.names(events)).endsWith("error").containsOnlyOnce("error");
        assertThat(TurnStream.JsonText.field(events.getLast().data(), "message"))
                .contains("database")
                .doesNotContain("model");
        assertThat(jdbc.queryForObject("SELECT state FROM chat_turn", String.class))
                .isEqualTo("INCOMPLETE");
    }

    /**
     * Fix 9: two rounds that both speak are two paragraphs, streamed and stored alike, and a
     * bracket held at the end of a round goes out with that round instead of joining the next.
     */
    @Test
    void twoSpeakingRoundsAreTwoParagraphs() {
        long conversation = conversation(null);
        MODEL.enqueue(ModelStub.textThenToolCalls("Let me search.", "profile", "{}"));
        MODEL.enqueue(ModelStub.text(Duration.ZERO, "Three offers match."));

        List<TurnStream.Event> events = TurnStream.ask(port, conversation, "Which fit?");

        assertThat(TurnStream.text(events)).isEqualTo("Let me search.\n\nThree offers match.");
        assertThat(jdbc.queryForObject("SELECT answer_md FROM chat_turn", String.class))
                .isEqualTo("Let me search.\n\nThree offers match.");
    }

    @Test
    void aBracketHeldAtTheEndOfARoundIsFlushedAsTextAndNeverJoinsTheNextRound() {
        long offer = offer("Kotlin Developer");
        long conversation = conversation(null);
        MODEL.enqueue(ModelStub.textThenToolCalls("See [", "profile", "{}"));
        MODEL.enqueue(ModelStub.text(Duration.ZERO, "[offer:" + offer + "]] next."));

        List<TurnStream.Event> events = TurnStream.ask(port, conversation, "Which fit?");

        assertThat(TurnStream.text(events)).isEqualTo("See [\n\n[offer:" + offer + "]] next.");
        assertThat(jdbc.queryForObject("SELECT answer_md FROM chat_turn", String.class))
                .isEqualTo(TurnStream.text(events));
    }

    /** Finding 4: a turn a previous process left streaming is marked incomplete at startup. */
    @Test
    void theStartupSweepEndsTurnsADeadProcessLeftStreaming() {
        long conversation = conversation(null);
        // Started before this process was: a dead process's turn is always older than the sweep's own start.
        jdbc.update(
                "INSERT INTO chat_turn (conversation_id, ordinal, question, answer_md, created_at)"
                        + " VALUES (?, 1, 'Q?', 'Half', now() - interval '1 hour')",
                conversation);

        turns.sweep();

        assertThat(jdbc.queryForMap("SELECT state, answer_md, finished_at IS NOT NULL AS ended FROM chat_turn"))
                .containsEntry("state", "INCOMPLETE")
                .containsEntry("answer_md", "Half")
                .containsEntry("ended", true);
    }

    /**
     * Fix 5B-7: the web server takes requests before the ready event, so a turn this process
     * already started can be streaming when the sweep runs. It is not a dead process's, and stays.
     */
    @Test
    void theStartupSweepLeavesATurnThisProcessStarted() {
        long conversation = conversation(null);
        jdbc.update(
                "INSERT INTO chat_turn (conversation_id, ordinal, question, answer_md) VALUES (?, 1, 'Q?', 'Live')",
                conversation);

        turns.sweep();

        assertThat(jdbc.queryForObject("SELECT state FROM chat_turn", String.class))
                .isEqualTo("STREAMING");
    }

    /**
     * Fix 5B-6: a regeneration that produced no answer — cut off before it said anything — does
     * not take the exchange it replaced out of the model's history; the exchange would vanish.
     */
    @Test
    void aReplacementWithoutAnAnswerLeavesTheReplacedExchangeInTheHistory() {
        long conversation = conversation(null);
        long first = pastTurn(conversation, 1, "Q1?", "A1.");
        jdbc.update("""
                INSERT INTO chat_turn (conversation_id, ordinal, question, answer_md, state, finished_at,
                                       replaces_turn_id)
                VALUES (?, 2, 'Q1?', '', 'INCOMPLETE', now(), ?)
                """, conversation, first);
        long next = pastTurn(conversation, 3, "Q2?", "");

        assertThat(conversations.history(conversation, next)).containsExactly(new PastTurn("Q1?", "A1."));
    }

    /** Fix 5B-6, the guard: once a later attempt in the chain answered, only that answer is history. */
    @Test
    void anAnsweredRegenerationFurtherDownTheChainStillReplacesTheExchange() {
        long conversation = conversation(null);
        long first = pastTurn(conversation, 1, "Q1?", "A1.");
        long empty = jdbc.queryForObject("""
                INSERT INTO chat_turn (conversation_id, ordinal, question, answer_md, state, finished_at,
                                       replaces_turn_id)
                VALUES (?, 2, 'Q1?', '', 'INCOMPLETE', now(), ?) RETURNING id
                """, Long.class, conversation, first);
        jdbc.update("""
                INSERT INTO chat_turn (conversation_id, ordinal, question, answer_md, state, finished_at,
                                       replaces_turn_id)
                VALUES (?, 3, 'Q1?', 'A1 again.', 'DONE', now(), ?)
                """, conversation, empty);
        long next = pastTurn(conversation, 4, "Q2?", "");

        assertThat(conversations.history(conversation, next)).containsExactly(new PastTurn("Q1?", "A1 again."));
    }

    /** Finding 8: regenerating turn 2 of 5 shows the model the dialogue up to turn 2 and nothing after. */
    @Test
    void regeneratingAnEarlierTurnShowsTheModelOnlyWhatCameBeforeIt() throws Exception {
        long conversation = conversation(null);
        String[] words = {"one", "two", "three", "four", "five"};
        long second = 0;
        for (int i = 0; i < words.length; i++) {
            long id = pastTurn(conversation, i + 1, "Question " + words[i] + "?", "Answer " + words[i] + ".");
            if (i == 1) {
                second = id;
            }
        }
        MODEL.enqueue(ModelStub.text(Duration.ZERO, "Answer two again."));

        TurnStream.post(
                port,
                "/api/v1/chat/conversations/" + conversation + "/turns/" + second + "/regenerate",
                "",
                event -> {});

        List<String> said = new ArrayList<>();
        for (JsonNode message : JSON.readTree(MODEL.bodies().getLast()).path("messages")) {
            if (!message.path("role").asText().equals("system")) {
                said.add(message.path("content").asText());
            }
        }
        assertThat(said).containsExactly("Question one?", "Answer one.", "Question two?");
    }

    /** Finding 11: a long answer is written in a few flushes, not one statement per chunk. */
    @Test
    void aLongAnswerIsWrittenInFewStatements() {
        long conversation = conversation(null);
        String[] parts = new String[200];
        for (int i = 0; i < parts.length; i++) {
            parts[i] = "w" + i + " ";
        }
        MODEL.enqueue(ModelStub.text(Duration.ofMillis(2), parts));

        List<TurnStream.Event> events = TurnStream.ask(port, conversation, "Long?");

        long appends = mockingDetails(conversations).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("append"))
                .count();
        assertThat(appends).as("append statements for 200 chunks").isLessThan(20);
        assertThat(jdbc.queryForObject("SELECT answer_md FROM chat_turn", String.class))
                .isEqualTo(TurnStream.text(events))
                .isEqualTo(String.join("", parts));
    }

    /**
     * Fix 3B-3: tool arguments carrying a NUL — which neither JSONB nor TEXT accept — are stored
     * without it, so the turn's tool-call rows no longer take its terminal state down with them.
     */
    @Test
    void toolArgumentsWithANulStillEndTheTurnAndAreStoredWithoutIt() {
        long conversation = conversation(null);
        MODEL.enqueue(ModelStub.toolCalls("search_offers", "{\"text\":\"java\\u0000\"}"));
        MODEL.enqueue(ModelStub.text(Duration.ZERO, "Nothing matches."));

        List<TurnStream.Event> events = TurnStream.ask(port, conversation, "Which Java roles?");

        assertThat(TurnStream.names(events).getLast()).isEqualTo("done");
        assertThat(jdbc.queryForObject("SELECT state FROM chat_turn", String.class))
                .isEqualTo("DONE");
        assertThat(jdbc.queryForMap("SELECT label, arguments ->> 'text' AS text FROM chat_tool_call"))
                .containsEntry("label", "Searched offers · java")
                .containsEntry("text", "java");
    }

    /**
     * Fix 3B-3: when the terminal write is rejected, the fallback is the state alone in a statement
     * of its own — retrying the same rejected transaction left the turn STREAMING until a restart.
     */
    @Test
    void aRejectedFinishStillEndsTheRowByItsStateAlone() {
        long conversation = conversation(null);
        doThrow(new org.springframework.dao.DataIntegrityViolationException("a row the table rejects"))
                .when(conversations)
                .finish(
                        anyLong(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.anyList(),
                        org.mockito.ArgumentMatchers.anyList());
        // The broken path writes through the overload that carries the end reason; it has to be
        // rejected too, or the whole write succeeds there and the fallback is never reached.
        doThrow(new org.springframework.dao.DataIntegrityViolationException("a row the table rejects"))
                .when(conversations)
                .finish(
                        anyLong(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.anyList(),
                        org.mockito.ArgumentMatchers.anyList());
        MODEL.enqueue(ModelStub.text(Duration.ZERO, "An answer."));

        List<TurnStream.Event> events = TurnStream.ask(port, conversation, "Anything?");

        assertThat(TurnStream.names(events).getLast()).isEqualTo("error");
        assertThat(jdbc.queryForMap("SELECT state, answer_md, finished_at IS NOT NULL AS ended FROM chat_turn"))
                .containsEntry("state", "INCOMPLETE")
                .containsEntry("answer_md", "An answer.")
                .containsEntry("ended", true);
        // The row ended through the fallback, not through a whole write that happened to succeed.
        org.mockito.Mockito.verify(conversations).incomplete(anyLong());
    }

    /**
     * Fix 3B-7: the pinned lookup reads an offer whatever its status, so its citation is a link
     * even when the offer was filtered out — the turn's own lookup returned it.
     */
    @Test
    void aFilteredPinnedOfferIsStillCitable() {
        long offer = offer("Filtered Platform Engineer");
        jdbc.update("UPDATE offer SET status = 'FILTERED_OUT' WHERE id = ?", offer);
        long conversation = conversation(offer);
        MODEL.enqueue(ModelStub.text(Duration.ZERO, "This one [[offer:" + offer + "]] was filtered."));

        List<TurnStream.Event> events = TurnStream.ask(port, conversation, "Why not?");

        assertThat(TurnStream.text(events)).isEqualTo("This one [1](cite:offer/" + offer + ") was filtered.");
        assertThat(TurnStream.names(events).getLast()).isEqualTo("done");
    }

    /**
     * Fix 3B-10: a regenerated turn goes back to the model in the place of the turn it replaced —
     * through a chain of regenerations — not at its own later ordinal after dialogue that followed.
     */
    @Test
    void aRegeneratedTurnGoesBackToTheModelInThePlaceOfTheTurnItReplaced() throws Exception {
        long conversation = conversation(null);
        long first = pastTurn(conversation, 1, "Q1?", "A1.");
        pastTurn(conversation, 2, "Q2?", "A2.");
        String turns = "/api/v1/chat/conversations/" + conversation + "/turns/";
        MODEL.enqueue(ModelStub.text(Duration.ZERO, "A1 again."));
        TurnStream.post(port, turns + first + "/regenerate", "", event -> {});
        long second = jdbc.queryForObject(
                "SELECT id FROM chat_turn WHERE conversation_id = ? AND replaces_turn_id = ?",
                Long.class,
                conversation,
                first);
        MODEL.enqueue(ModelStub.text(Duration.ZERO, "A1 once more."));
        TurnStream.post(port, turns + second + "/regenerate", "", event -> {});
        MODEL.enqueue(ModelStub.text(Duration.ZERO, "A3."));

        TurnStream.ask(port, conversation, "Q3?");

        assertThat(said(MODEL.bodies().getLast())).containsExactly("Q1?", "A1 once more.", "Q2?", "A2.", "Q3?");
    }

    /**
     * Fix 4B-5: a NUL in the model's text — which a {@code TEXT} column refuses — is dropped before
     * the text is streamed or buffered, so one such token no longer fails the flush and loses the
     * answer, and what was streamed still equals what was stored.
     */
    @Test
    void aNulInTheModelsTextIsDroppedAndTheAnswerIsStoredWhole() {
        long conversation = conversation(null);
        // A pause past the flush interval, so the NUL reaches a write while the model still streams.
        MODEL.enqueue(ModelStub.text(ChatTurnService.FLUSH_INTERVAL.plusMillis(100), "Half\0 ", "an ", "answer."));

        List<TurnStream.Event> events = TurnStream.ask(port, conversation, "Anything?");

        assertThat(TurnStream.names(events).getLast()).isEqualTo("done");
        assertThat(TurnStream.text(events)).isEqualTo("Half an answer.");
        assertThat(jdbc.queryForMap("SELECT state, answer_md FROM chat_turn"))
                .containsEntry("state", "DONE")
                .containsEntry("answer_md", "Half an answer.");
    }

    /**
     * Fix 4B-6: a turn that cited a row and then ran out of tool rounds streams its sources before
     * its error, so the live pills have the rows they point at — as the stored turn already does.
     */
    @Test
    void aTurnThatCitesThenRunsOutOfRoundsStreamsItsSourcesBeforeTheError() {
        long offer = offer("Cited Platform Engineer");
        long conversation = conversation(offer);
        MODEL.enqueue(ModelStub.textThenToolCalls("This one [[offer:" + offer + "]] fits.", "profile", "{}"));
        // The shipped ceiling is six rounds; the seventh request for tools ends the turn.
        for (int round = 0; round < 7; round++) {
            MODEL.enqueue(ModelStub.toolCalls("profile", "{}"));
        }

        List<TurnStream.Event> events = TurnStream.ask(port, conversation, "Is it a fit?");

        assertThat(TurnStream.names(events)).endsWith("sources", "error").containsOnlyOnce("sources");
        assertThat(TurnStream.JsonText.field(events.getLast().data(), "reason")).isEqualTo("ROUNDS");
        assertThat(events.get(events.size() - 2).data()).contains("\"id\":" + offer);
        assertThat(jdbc.queryForObject("SELECT state FROM chat_turn", String.class))
                .isEqualTo("INCOMPLETE");
    }

    /**
     * Fix 4B-7: deleting a conversation while its turn streams is the reader's doing, not a database
     * failure. The cascade takes the turn's row, and the turn ends with one terminal event, nothing
     * logged at ERROR, and no fallback write retried against a row that is gone.
     */
    @Test
    void deletingTheConversationMidTurnEndsTheTurnQuietly() {
        long conversation = conversation(null);
        MODEL.enqueue(ModelStub.toolCalls("profile", "{}"));
        MODEL.enqueue(ModelStub.text(Duration.ofMillis(300), "Still ", "talking ", "here."));
        Logger logger = (Logger) LoggerFactory.getLogger(ChatTurnService.class);
        ListAppender<ILoggingEvent> logged = new ListAppender<>();
        logged.start();
        logger.addAppender(logged);
        AtomicBoolean deleted = new AtomicBoolean();
        List<TurnStream.Event> events;
        try {
            events = TurnStream.post(
                    port,
                    "/api/v1/chat/conversations/" + conversation + "/turns",
                    "{\"question\":\"Anything?\"}",
                    event -> {
                        if (event.name().equals("text") && deleted.compareAndSet(false, true)) {
                            jdbc.update("DELETE FROM chat_conversation WHERE id = ?", conversation);
                        }
                    });
        } finally {
            logger.detachAppender(logged);
        }

        assertThat(deleted).isTrue();
        List<String> names = TurnStream.names(events);
        assertThat(names.getLast()).isIn("done", "error");
        assertThat(names.stream().filter(name -> name.equals("done") || name.equals("error")))
                .hasSize(1);
        assertThat(logged.list)
                .as("ERROR lines for a conversation the reader deleted")
                .noneMatch(line -> line.getLevel() == Level.ERROR);
        long fallbacks = mockingDetails(conversations).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("incomplete"))
                .count();
        assertThat(fallbacks).as("fallback writes against a vanished turn").isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_turn", Integer.class))
                .isZero();
    }

    /** What the model was shown, system prompt left out, in order. */
    private static List<String> said(String body) throws Exception {
        List<String> said = new ArrayList<>();
        for (JsonNode message : JSON.readTree(body).path("messages")) {
            if (!message.path("role").asText().equals("system")) {
                said.add(message.path("content").asText());
            }
        }
        return said;
    }

    private long offer(String title) {
        return jdbc.queryForObject(
                """
                INSERT INTO offer (source_id, external_id, title, description, full_text, url, fingerprint,
                                   status, score_value, portal, ingested_at)
                VALUES (?, ?, ?, 'Replacing a monolith.', 'Replacing a monolith.', ?, ?, 'PASSED', 80,
                        'portal-a', now())
                RETURNING id
                """,
                Long.class,
                sourceId,
                "ext-" + title,
                title,
                "https://example.invalid/" + title.hashCode(),
                title.toLowerCase());
    }

    private long conversation(Long pinned) {
        return jdbc.queryForObject(
                "INSERT INTO chat_conversation (title, pinned_offer_id) VALUES ('', ?) RETURNING id",
                Long.class,
                pinned);
    }

    private long pastTurn(long conversation, int ordinal, String question, String answer) {
        return jdbc.queryForObject("""
                INSERT INTO chat_turn (conversation_id, ordinal, question, answer_md, state, finished_at)
                VALUES (?, ?, ?, ?, 'DONE', now())
                RETURNING id
                """, Long.class, conversation, ordinal, question, answer);
    }
}
