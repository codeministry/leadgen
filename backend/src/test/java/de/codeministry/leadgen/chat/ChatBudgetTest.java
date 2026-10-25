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
import de.codeministry.leadgen.config.ConfigFixtures;
import de.codeministry.leadgen.config.ConfigRegistry;
import de.codeministry.leadgen.llm.Connections;
import de.codeministry.leadgen.llm.LlmBudget;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The chat's own allowance: the daily count beside {@code llm.budget} and never through it, and
 * the bound on tool rounds per turn.
 *
 * <p>The ceilings are rewritten in the materialised {@code pipeline.yaml} rather than mocked, as in
 * {@code LlmBudgetTest}, because the binding is part of what is under test: absent and zero are
 * opposite answers. The end-to-end half — a refused call or a spent round ending a real turn
 * against the stub model with an {@code error} event and its reason — runs the turn on the test thread.
 */
@SpringBootTest
@Testcontainers
class ChatBudgetTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = Databases.postgres();

    /** Created once, never inside the property supplier — see {@code LlmBudgetTest}. */
    static final Path CONFIG_DIRECTORY = freshConfigDirectory();

    private static final ModelStub MODEL = ModelStub.start();

    /** The chat's second connection, where a test points {@code chat.connection} at it (spec 025). */
    private static final ModelStub SECOND = ModelStub.start();

    /** The shipped {@code pipeline.yaml} pointed at the stub; copied over the fixture before each test. */
    private static final Path STUBBED = MODEL.configuration().resolve("pipeline.yaml");

    @Autowired
    private ChatBudget budget;

    @Autowired
    private ChatTurnService turns;

    @Autowired
    private LlmBudget pipelineBudget;

    @Autowired
    private ConfigRegistry config;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ConversationRepository conversations;

    @Autowired
    private WebApplicationContext context;

    @DynamicPropertySource
    static void configuration(DynamicPropertyRegistry registry) {
        registry.add("leadgen.config-dir", CONFIG_DIRECTORY::toString);
    }

    @AfterAll
    static void stop() {
        MODEL.close();
        SECOND.close();
    }

    @BeforeEach
    void reset() throws IOException {
        MODEL.reset();
        SECOND.reset();
        jdbc.update("DELETE FROM chat_conversation");
        jdbc.update("DELETE FROM chat_call_budget");
        jdbc.update("DELETE FROM chat_connection_call_budget");
        jdbc.update("DELETE FROM llm_call_budget");
        ConfigFixtures.materialize(CONFIG_DIRECTORY);
        Files.copy(STUBBED, CONFIG_DIRECTORY.resolve("pipeline.yaml"), StandardCopyOption.REPLACE_EXISTING);
        config.reload();
    }

    @Test
    void aRefusedCallEndsARealTurnWithTheBudgetReason() {
        chat("max_calls_per_day", "2");
        pipeline("max_calls_per_day", "10");
        int pipelineBefore = pipelineBudget.used();
        for (int round = 0; round < 3; round++) {
            MODEL.enqueue(ModelStub.toolCalls("search_offers", "{\"text\":\"java\"}"));
        }

        List<ChatEvent> events = turn();

        assertThat(events.getLast())
                .isInstanceOfSatisfying(
                        ChatError.class, error -> assertThat(error.reason()).isEqualTo(ChatErrorReason.BUDGET));
        assertThat(events).noneMatch(event -> event instanceof ChatDone);
        // Two calls went out; the third was refused before it was sent.
        assertThat(MODEL.bodies()).hasSize(2);
        assertThat(budget.used()).isEqualTo(2);
        assertThat(pipelineBudget.used()).isEqualTo(pipelineBefore).isZero();
        assertThat(jdbc.queryForObject("SELECT state FROM chat_turn", String.class))
                .isEqualTo("INCOMPLETE");
    }

    @Test
    void aSpentRoundEndsARealTurnWithTheRoundReason() {
        chat("max_tool_rounds", "1");
        pipeline("max_calls_per_day", "10");
        for (int round = 0; round < 3; round++) {
            MODEL.enqueue(ModelStub.toolCalls("search_offers", "{\"text\":\"java\"}"));
        }

        List<ChatEvent> events = turn();

        assertThat(events.getLast())
                .isInstanceOfSatisfying(
                        ChatError.class, error -> assertThat(error.reason()).isEqualTo(ChatErrorReason.ROUNDS));
        // The first round ran its tool; the second asked for one and was refused, so one call is logged.
        assertThat(MODEL.bodies()).hasSize(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_tool_call", Integer.class))
                .isEqualTo(1);
        assertThat(pipelineBudget.used()).isZero();
    }

    /**
     * ISC-506: a turn and a regenerate asked with a name no connection listed, or with a listed name
     * under the other connection, are refused over HTTP before anything starts — no model request at
     * either stub, and neither chat counter nor {@code llm.budget} moves.
     */
    @Test
    void aPickTheServerDidNotDiscoverIsRefusedAndMovesNoCounter() {
        rewrite(text -> ModelStub.pointSecondAt(text, "openai-compatible", SECOND.baseUrl()));
        MODEL.lists(ModelStub.MODEL, "model-a");
        SECOND.lists("model-b");
        long conversation =
                jdbc.queryForObject("INSERT INTO chat_conversation (title) VALUES ('') RETURNING id", Long.class);
        long earlier = conversations.startTurn(conversation, "Which Java roles?", ModelStub.MODEL);
        MockMvcTester mvc = MockMvcTester.from(context);
        // The drawer reads the list before it offers a pick; the refusals then read only that list.
        assertThat(mvc.get().uri("/api/v1/chat/models")).hasStatusOk();
        int pipelineBefore = pipelineBudget.used();

        for (String pick : List.of(
                "\"connection\":\"SECOND\",\"model\":\"model-unlisted\"",
                "\"connection\":\"PRIMARY\",\"model\":\"model-b\"")) {
            assertThat(mvc.post()
                            .uri("/api/v1/chat/conversations/" + conversation + "/turns")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"question\":\"Which Java roles?\"," + pick + "}"))
                    .as("ask, " + pick)
                    .hasStatus(400);
            assertThat(mvc.post()
                            .uri("/api/v1/chat/conversations/" + conversation + "/turns/" + earlier + "/regenerate")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{" + pick + "}"))
                    .as("regenerate, " + pick)
                    .hasStatus(400);
        }

        assertThat(MODEL.bodies()).as("requests at the primary stub").isEmpty();
        assertThat(SECOND.bodies()).as("requests at the second stub").isEmpty();
        assertThat(List.of(MODEL.listings(), SECOND.listings()))
                .as("each list read once, by the drawer")
                .containsExactly(1, 1);
        assertThat(budget.used()).isZero();
        assertThat(jdbc.queryForObject("SELECT coalesce(sum(calls), 0) FROM chat_call_budget", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT coalesce(sum(calls), 0) FROM chat_connection_call_budget", Integer.class))
                .isZero();
        assertThat(pipelineBudget.used()).isEqualTo(pipelineBefore).isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM chat_turn WHERE conversation_id = ?", Integer.class, conversation))
                .as("no turn written")
                .isEqualTo(1);
    }

    /**
     * ISC-510: the second connection's ceiling of 2 beside the chat's 10. The third turn on the
     * second connection ends {@code BUDGET} with two requests at its stub, and a turn on the primary
     * in the same conversation still answers; the refused third counted on neither counter,
     * {@code llm.budget} never moved, and the status names both counters.
     */
    @Test
    void aSpentSecondConnectionEndsItsTurnWhileThePrimaryStillAnswers() {
        rewrite(text -> ModelStub.pointSecondAt(text, "openai-compatible", SECOND.baseUrl()));
        chat("max_calls_per_day", "10");
        second("max_calls_per_day", "2");
        int pipelineBefore = pipelineBudget.used();
        for (int reply = 0; reply < 3; reply++) {
            SECOND.enqueue(ModelStub.text(Duration.ZERO, "From the second."));
        }
        MODEL.enqueue(ModelStub.text(Duration.ZERO, "From the primary."));
        long conversation =
                jdbc.queryForObject("INSERT INTO chat_conversation (title) VALUES ('') RETURNING id", Long.class);

        List<List<ChatEvent>> picked = new ArrayList<>();
        for (int turn = 0; turn < 3; turn++) {
            List<ChatEvent> events = new ArrayList<>();
            turns.ask(conversation, "Which Java roles?", new ModelPick("SECOND", "model-b"), events::add);
            picked.add(events);
        }
        List<ChatEvent> primary = new ArrayList<>();
        turns.ask(conversation, "And on the primary?", primary::add);

        assertThat(picked.get(0).getLast()).isInstanceOf(ChatDone.class);
        assertThat(picked.get(1).getLast()).isInstanceOf(ChatDone.class);
        assertThat(picked.get(2).getLast())
                .isInstanceOfSatisfying(
                        ChatError.class, error -> assertThat(error.reason()).isEqualTo(ChatErrorReason.BUDGET));
        assertThat(SECOND.bodies()).as("requests at the second stub").hasSize(2);
        assertThat(primary.getLast()).as("the primary still answers").isInstanceOf(ChatDone.class);
        assertThat(MODEL.bodies()).hasSize(1);
        assertThat(List.of(budget.used(), budget.used(Connections.Name.SECOND)))
                .as("the chat's counter and the second's; the refused third on neither")
                .containsExactly(3, 2);
        assertThat(pipelineBudget.used()).isEqualTo(pipelineBefore).isZero();

        var status = assertThat(MockMvcTester.from(context).get().uri("/api/v1/chat/status"))
                .hasStatusOk()
                .bodyJson();
        status.extractingPath("$.callsUsed").isEqualTo(3);
        status.extractingPath("$.callsLimit").isEqualTo(10);
        status.extractingPath("$.secondCalls").isEqualTo(2);
        status.extractingPath("$.secondLimit").isEqualTo(2);
    }

    /** ISC-510: without the key, the second connection's ceiling is the shipped default, never none. */
    @Test
    void anAbsentSecondCeilingIsTheShippedDefault() {
        rewrite(text -> ModelStub.pointSecondAt(text, "openai-compatible", SECOND.baseUrl()));
        rewrite(text -> text.replaceAll("(?m)^    max_calls_per_day: \\$\\{CHAT_LLM_MAX_CALLS_PER_DAY[^}]*}\\n", ""));
        jdbc.update(
                "INSERT INTO chat_connection_call_budget (day, connection, calls) VALUES (current_date, 'SECOND', ?)",
                ChatBudget.DEFAULT_SECOND_CALLS_PER_DAY - 1);

        assertThat(budget.secondLimit()).hasValue(ChatBudget.DEFAULT_SECOND_CALLS_PER_DAY);
        assertThat(ChatBudget.DEFAULT_SECOND_CALLS_PER_DAY).isEqualTo(50);
        assertThat(budget.take(Connections.Name.SECOND)).isTrue();
        assertThat(budget.take(Connections.Name.SECOND)).isFalse();
        assertThat(budget.used(Connections.Name.SECOND)).isEqualTo(50);
        assertThat(budget.used())
                .as("the refused call given back on the chat's counter")
                .isEqualTo(1);
    }

    /** ISC-510: a call the chat's own ceiling refuses never moves the second connection's counter. */
    @Test
    void aSecondCallTheChatRefusesCountsOnNeither() {
        rewrite(text -> ModelStub.pointSecondAt(text, "openai-compatible", SECOND.baseUrl()));
        chat("max_calls_per_day", "1");
        second("max_calls_per_day", "5");

        assertThat(budget.take(Connections.Name.SECOND)).isTrue();
        assertThat(budget.take(Connections.Name.SECOND)).isFalse();
        assertThat(budget.take(Connections.Name.PRIMARY)).isFalse();

        assertThat(List.of(budget.used(), budget.used(Connections.Name.SECOND))).containsExactly(1, 1);
        assertThat(budget.used(Connections.Name.PRIMARY)).isEqualTo(1);
    }

    /** ISC-510: without a second connection the status names only the chat's counter. */
    @Test
    void withoutASecondConnectionThereIsNoSecondCeiling() {
        assertThat(budget.secondLimit()).isEmpty();
        var status = assertThat(MockMvcTester.from(context).get().uri("/api/v1/chat/status"))
                .hasStatusOk()
                .bodyJson();
        status.extractingPath("$.secondCalls").isNull();
        status.extractingPath("$.secondLimit").isNull();
    }

    /** One turn on this thread, its events collected. */
    private List<ChatEvent> turn() {
        long conversation =
                jdbc.queryForObject("INSERT INTO chat_conversation (title) VALUES ('') RETURNING id", Long.class);
        List<ChatEvent> events = new ArrayList<>();
        turns.ask(conversation, "Which Java roles?", events::add);
        return events;
    }

    @Test
    void theThirdCallOfTwoIsRefused() {
        chat("max_calls_per_day", "2");

        assertThat(budget.take()).isTrue();
        assertThat(budget.take()).isTrue();
        assertThat(budget.take()).isFalse();

        assertThat(budget.used()).isEqualTo(2);
    }

    @Test
    void chatCallsNeverMoveThePipelineCounter() {
        pipeline("max_calls_per_day", "10");
        chat("max_calls_per_day", "5");
        assertThat(pipelineBudget.take()).isTrue();
        int before = pipelineBudget.used();

        budget.take();
        budget.take();
        budget.take();

        assertThat(pipelineBudget.used()).isEqualTo(before).isEqualTo(1);
        assertThat(budget.used()).isEqualTo(3);
    }

    @Test
    void aSpentPipelineDayDoesNotSilenceTheChat() {
        // The other half of "beside, never through": the nightly run spending its day must not
        // leave the afternoon's questions unanswered.
        pipeline("max_calls_per_day", "0");
        chat("max_calls_per_day", "1");

        assertThat(pipelineBudget.take()).isFalse();
        assertThat(budget.take()).isTrue();
    }

    @Test
    void zeroMeansNoCallsAndCountsNothing() {
        chat("max_calls_per_day", "0");

        assertThat(budget.take()).isFalse();
        assertThat(budget.used()).isZero();
    }

    /**
     * Fix 3B-2: an absent {@code max_calls_per_day} is the shipped 200, not "no ceiling". The chat is
     * on wherever a scoring model exists, so an installation whose own pipeline.yaml predates the
     * {@code chat:} block must not get an unmetered one.
     */
    @Test
    void anAbsentCeilingIsTheShippedDefaultOf200() {
        rewrite(text -> text.replaceAll("(?m)^  max_calls_per_day: \\$\\{CHAT_MAX_CALLS_PER_DAY[^}]*}\\n", ""));
        jdbc.update("INSERT INTO chat_call_budget (day, calls) VALUES (current_date, 199)");

        assertThat(budget.take()).isTrue();
        assertThat(budget.take()).isFalse();
        assertThat(budget.used()).isEqualTo(200);
    }

    @Test
    void yesterdaysChatCallsAreNotTodays() {
        chat("max_calls_per_day", "1");
        jdbc.update("INSERT INTO chat_call_budget (day, calls) VALUES (current_date - 1, 999)");

        assertThat(budget.take()).isTrue();
        assertThat(budget.used()).isEqualTo(1);
    }

    @Test
    void oneToolRoundAllowedRefusesTheSecond() {
        chat("max_tool_rounds", "1");

        ChatBudget.Rounds rounds = budget.rounds();

        assertThat(rounds.next()).isTrue();
        assertThat(rounds.next()).isFalse();
        assertThat(rounds.next()).isFalse();
    }

    @Test
    void eachTurnCountsItsOwnRounds() {
        chat("max_tool_rounds", "2");

        ChatBudget.Rounds first = budget.rounds();
        first.next();
        first.next();

        assertThat(first.next()).isFalse();
        assertThat(budget.rounds().next()).isTrue();
    }

    /** Fix 3B-2: an absent round bound is the shipped 6, so a model that keeps asking for tools stops. */
    @Test
    void anAbsentRoundBoundIsTheShippedSix() {
        rewrite(text -> text.replaceAll("(?m)^  max_tool_rounds: .*\\n", ""));

        ChatBudget.Rounds rounds = budget.rounds();
        for (int round = 0; round < 6; round++) {
            assertThat(rounds.next()).isTrue();
        }
        assertThat(rounds.next()).isFalse();
    }

    /**
     * Fix 3B-2: configuration overrides whole files, so a local pipeline.yaml without a {@code chat:}
     * block used to mean no round bound at all — and a model that kept asking for tools looped for
     * ever. Six rounds run their tools; the seventh request asks for one more and is refused.
     */
    @Test
    void withoutAChatBlockATurnIsRefusedItsSeventhRound() {
        rewrite(text -> text.replaceAll("(?m)^chat:\\n(?:  .*\\n)*", ""));
        for (int round = 0; round < 10; round++) {
            MODEL.enqueue(ModelStub.toolCalls("profile", "{}"));
        }

        List<ChatEvent> events = turn();

        assertThat(events.getLast())
                .isInstanceOfSatisfying(
                        ChatError.class, error -> assertThat(error.reason()).isEqualTo(ChatErrorReason.ROUNDS));
        assertThat(MODEL.bodies()).hasSize(7);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_tool_call", Integer.class))
                .isEqualTo(6);
        assertThat(budget.used())
                .as("the shipped daily ceiling counts the calls")
                .isEqualTo(7);
    }

    /** Sets a key inside the top-level {@code chat:} block, leaving {@code llm.budget}'s alone. */
    private void chat(String key, String value) {
        rewrite(text -> {
            int block = text.indexOf("\nchat:\n");
            if (block < 0) {
                throw new IllegalStateException("no top-level `chat:` in the shipped pipeline.yaml");
            }
            return text.substring(0, block) + set(text.substring(block), key, value);
        });
    }

    /** Sets a key inside the {@code chat.connection} block. */
    private void second(String key, String value) {
        rewrite(text -> {
            int block = text.indexOf("\n  connection:\n");
            if (block < 0) {
                throw new IllegalStateException("no `chat.connection:` in the shipped pipeline.yaml");
            }
            return text.substring(0, block) + set(text.substring(block), key, value);
        });
    }

    /** Sets a key in the pipeline's {@code llm.budget} block, which comes before {@code chat:}. */
    private void pipeline(String key, String value) {
        rewrite(text -> set(text, key, value));
    }

    private void rewrite(UnaryOperator<String> edit) {
        Path pipeline = CONFIG_DIRECTORY.resolve("pipeline.yaml");
        try {
            Files.writeString(pipeline, edit.apply(Files.readString(pipeline, StandardCharsets.UTF_8)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        config.reload();
    }

    private static String set(String yaml, String key, String value) {
        Matcher matcher =
                Pattern.compile("(?m)^([ \\t]*)" + Pattern.quote(key) + ":.*$").matcher(yaml);
        if (!matcher.find()) {
            throw new IllegalStateException("no `" + key + ":` in the shipped pipeline.yaml");
        }
        return matcher.replaceFirst("$1" + key + ": " + Matcher.quoteReplacement(value));
    }

    private static Path freshConfigDirectory() {
        try {
            Path dir = Files.createTempDirectory("leadgen-chat-budget");
            dir.toFile().deleteOnExit();
            return ConfigFixtures.materialize(dir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
