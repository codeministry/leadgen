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
import de.codeministry.leadgen.config.ConfigFixtures;
import de.codeministry.leadgen.config.ConfigRegistry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
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
 * ISC-518: every turn stores its input and output tokens summed over every model call it made,
 * tool rounds included, as the provider reported them — on both connections, whose two kinds report
 * usage differently: the OpenAI module as one final chunk with empty {@code choices}, the Messages
 * API as input tokens on {@code message_start} and output tokens on {@code message_delta}.
 *
 * <p>Each stubbed call reports a count of its own, so a sum that dropped a round, counted one twice
 * or added up running figures as deltas shows as a wrong number. The same totals are read off the
 * stream's {@code done}, the reloaded conversation and the row. A turn stored before V40 carries no
 * count, and the API leaves the fields out rather than writing a zero.
 *
 * <p>ISC-519: each turn's cost is stored beside its counts at the price read when it started — "0"
 * on the unbilled primary, absent on a billed model without a price — and a later price moves none.
 *
 * <p>ISC-520: a turn that ends short of an answer — refused by its ceiling or its round bound, a
 * 500, a request timeout, the operator's stop — still stores the tokens and the cost of every call
 * the provider reported before it ended. Each case runs on a billed, priced connection, and each
 * ending comes after at least one call that answered with its usage.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ChatTurnUsageTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** The empty price table {@code pipeline.yaml} ships, written over by {@link #prices}. */
    private static final String SHIPPED_PRICES = "  prices: []\n";

    private static final ModelStub PRIMARY = ModelStub.start();
    private static final AnthropicStub HOSTED = AnthropicStub.start();

    /**
     * ISC-520's connection: an {@code openai-compatible} endpoint marked billed, so its faults —
     * a 500, a pause, a stream cut by a stop — can be scripted, which the Messages-API stub cannot.
     */
    private static final ModelStub PRICED = ModelStub.start();

    /** The model {@link #PRICED} lists, priced at 2 and 10 USD per million tokens. */
    private static final String PRICED_MODEL = "model-p";

    /** Created once, never inside the property supplier — see {@code LlmBudgetTest}. */
    private static final Path CONFIG_DIRECTORY = freshConfigDirectory();

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = Databases.postgres();

    @DynamicPropertySource
    static void configuration(DynamicPropertyRegistry registry) {
        registry.add("leadgen.config-dir", CONFIG_DIRECTORY::toString);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ConfigRegistry config;

    @Autowired
    private ChatTurnService turns;

    @AfterAll
    static void stop() {
        PRIMARY.close();
        HOSTED.close();
        PRICED.close();
    }

    @BeforeEach
    void reset() {
        PRIMARY.reset();
        HOSTED.reset();
        PRICED.reset();
        PRIMARY.lists(ModelStub.MODEL);
        HOSTED.lists("model-b", "model-c");
        PRICED.lists(PRICED_MODEL);
        jdbc.update("DELETE FROM chat_conversation");
        jdbc.update("DELETE FROM chat_call_budget");
        jdbc.update("DELETE FROM chat_connection_call_budget");
        secondConnection("anthropic", HOSTED.baseUrl());
    }

    /** Two tool rounds and the answer on the primary: 100+130+170 in, 7+9+21 out. */
    @Test
    void aTurnOnThePrimaryStoresTheSumOfItsThreeCalls() {
        PRIMARY.enqueue(ModelStub.toolCalls("profile", "{}").withUsage(100, 7));
        PRIMARY.enqueue(ModelStub.toolCalls("profile", "{}").withUsage(130, 9));
        PRIMARY.enqueue(ModelStub.text(Duration.ZERO, "Read ", "twice.").withUsage(170, 21));
        long conversation = conversation();

        List<TurnStream.Event> events = TurnStream.ask(port, conversation, "Read my profile twice.");

        assertThat(TurnStream.names(events).getLast()).isEqualTo("done");
        assertThat(PRIMARY.bodies()).as("three calls, two of them tool rounds").hasSize(3);
        assertThat(PRIMARY.bodies())
                .as("every streamed request asks for the usage")
                .allSatisfy(body -> assertThat(JSON.readTree(body)
                                .path("stream_options")
                                .path("include_usage")
                                .asBoolean())
                        .isTrue());
        assertTotals(events, conversation, 400, 37, "0");
    }

    /**
     * Two tool rounds and the answer on the second connection, a Messages-API kind: 200+260+320 in,
     * 11+13+40 out — the input from each {@code message_start}, the output from each
     * {@code message_delta}, never one without the other.
     */
    @Test
    void aTurnOnTheSecondConnectionStoresTheSumOfItsThreeCalls() {
        HOSTED.enqueue(AnthropicStub.Reply.toolUse("profile", "{}").withUsage(200, 11));
        HOSTED.enqueue(AnthropicStub.Reply.toolUse("profile", "{}").withUsage(260, 13));
        HOSTED.enqueue(AnthropicStub.Reply.text("Read ", "twice.").withUsage(320, 40));
        long conversation = conversation();

        List<TurnStream.Event> events = TurnStream.post(
                port,
                "/api/v1/chat/conversations/" + conversation + "/turns",
                "{\"question\":\"Read my profile twice.\",\"connection\":\"SECOND\",\"model\":\"model-b\"}",
                event -> {});

        assertThat(TurnStream.names(events).getLast()).isEqualTo("done");
        assertThat(HOSTED.bodies()).as("three calls, two of them tool rounds").hasSize(3);
        assertThat(PRIMARY.bodies()).as("nothing asked at the primary").isEmpty();
        assertTotals(events, conversation, 780, 64, null);
    }

    /**
     * A turn stored before V40 has neither count: the row holds null, and the reloaded turn leaves
     * all three fields out — no data, never a zero the status popover would show as a free turn.
     */
    @Test
    void aTurnStoredWithoutTheColumnsCarriesNoUsage() {
        long conversation = conversation();
        long turn = jdbc.queryForObject(
                "INSERT INTO chat_turn (conversation_id, ordinal, question, answer_md, state, model, finished_at)"
                        + " VALUES (?, 1, 'Before V40?', 'Yes.', 'DONE', 'old-model', now()) RETURNING id",
                Long.class,
                conversation);

        Map<String, Object> row =
                jdbc.queryForMap("SELECT input_tokens, output_tokens, cost_usd FROM chat_turn WHERE id = ?", turn);
        assertThat(row.get("input_tokens")).isNull();
        assertThat(row.get("output_tokens")).isNull();
        assertThat(row.get("cost_usd")).isNull();

        JsonNode stored =
                get("/api/v1/chat/conversations/" + conversation).path("turns").get(0);
        assertThat(stored.path("answer").asText()).isEqualTo("Yes.");
        assertThat(stored.has("inputTokens")).as("inputTokens omitted").isFalse();
        assertThat(stored.has("outputTokens")).as("outputTokens omitted").isFalse();
        assertThat(stored.has("costUsd")).as("costUsd omitted").isFalse();
    }

    /**
     * ISC-519: a turn's cost is stored with it at the price in force when it ran. The second
     * connection's {@code model-b} is priced at 3 and 15 USD per million; one turn there, one on the
     * unbilled primary and one on the billed but unpriced {@code model-c}. Then every price changes
     * and {@code model-c} and the primary gain one, and nothing stored moves.
     *
     * <p>1234 × 3 / 1M + 567 × 15 / 1M = 0.003702 + 0.008505 = 0.012207 USD. The wire form is the
     * plain decimal without trailing zeros, so a free turn is {@code "0"}, and an unpriced one leaves
     * the field out.
     */
    @Test
    void aTurnIsCostedAtThePriceInForceWhenItRan() {
        prices("""
                    - connection: SECOND
                      model: model-b
                      input: 3
                      output: 15
                """);
        long conversation = conversation();

        HOSTED.enqueue(AnthropicStub.Reply.text("Priced.").withUsage(1234, 567));
        JsonNode priced = done(onSecond(conversation, "model-b"));
        PRIMARY.enqueue(ModelStub.text(Duration.ZERO, "Free.").withUsage(100, 7));
        JsonNode free = done(TurnStream.ask(port, conversation, "And locally?"));
        HOSTED.enqueue(AnthropicStub.Reply.text("Unpriced.").withUsage(50, 5));
        JsonNode unpriced = done(onSecond(conversation, "model-c"));

        assertThat(cost(priced)).as("tokens × price on a priced billed model").isEqualTo("0.012207");
        assertThat(cost(free)).as("0 on an unbilled connection").isEqualTo("0");
        assertThat(unpriced.has("costUsd"))
                .as("none on an unpriced billed model")
                .isFalse();
        assertThat(Arrays.asList(incomplete(priced), incomplete(free), incomplete(unpriced)))
                .as("no usageIncomplete on a fully reported turn, priced, free or unpriced")
                .containsOnlyNulls();
        assertThat(storedMarks(conversation)).as("nor on reload").containsExactly(null, null, null);
        List<String> reloaded = Arrays.asList("0.012207", "0", null);
        assertThat(storedCosts(conversation)).as("on reload").isEqualTo(reloaded);
        List<String> rows = rowCosts(conversation);
        assertThat(rows).as("on the rows").containsExactly("0.01220700", "0.00000000", "null");

        prices("""
                    - connection: SECOND
                      model: model-b
                      input: 6
                      output: 30
                    - connection: SECOND
                      model: model-c
                      input: 1
                      output: 2
                    - connection: PRIMARY
                      model: %s
                      input: 1
                      output: 1
                """.formatted(ModelStub.MODEL));

        assertThat(storedCosts(conversation))
                .as("a price changed or added later moves nothing on reload")
                .isEqualTo(reloaded);
        assertThat(rowCosts(conversation))
                .as("a price changed or added later moves nothing on the rows")
                .isEqualTo(rows);
    }

    /**
     * ISC-520, the ceiling: {@code chat.connection.max_calls_per_day} is 1, so the tool round's call
     * goes out and the next one is refused. 1000 × 2 / 1M + 100 × 10 / 1M = 0.003 USD.
     */
    @Test
    void aTurnRefusedByItsCeilingStoresTheCallBeforeIt() {
        pricedSecond(
                line("(?m)^    max_calls_per_day: \\$\\{CHAT_LLM_MAX_CALLS_PER_DAY.*$", "    max_calls_per_day: 1"));
        PRICED.enqueue(ModelStub.toolCalls("profile", "{}").withUsage(1000, 100));
        PRICED.enqueue(ModelStub.text(Duration.ZERO, "Never asked.").withUsage(9, 9));
        long conversation = conversation();

        List<TurnStream.Event> events = onPriced(conversation);

        assertThat(PRICED.bodies()).as("one call, the second refused").hasSize(1);
        assertEndedShort(events, conversation, "BUDGET", 1000, 100, "0.003");
    }

    /**
     * ISC-520, the round bound: {@code chat.max_tool_rounds} is 1 and the model asks for a second
     * round, so both calls answered and the turn ends on the bound. 2200 × 2 / 1M + 150 × 10 / 1M =
     * 0.0059 USD.
     */
    @Test
    void aTurnEndedByItsRoundBoundStoresBothCalls() {
        pricedSecond(line("(?m)^  max_tool_rounds: .*$", "  max_tool_rounds: 1"));
        PRICED.enqueue(ModelStub.toolCalls("profile", "{}").withUsage(1000, 100));
        PRICED.enqueue(ModelStub.toolCalls("profile", "{}").withUsage(1200, 50));
        long conversation = conversation();

        List<TurnStream.Event> events = onPriced(conversation);

        assertThat(PRICED.bodies()).as("two calls, both answered").hasSize(2);
        assertEndedShort(events, conversation, "ROUNDS", 2200, 150, "0.0059");
    }

    /** ISC-520, a model error: the second call is answered with a 500, the first one's usage stays. */
    @Test
    void aTurnWhoseSecondCallFailsStoresTheFirst() {
        pricedSecond(UnaryOperator.identity());
        PRICED.enqueue(ModelStub.toolCalls("profile", "{}").withUsage(1000, 100));
        // Nothing more queued: the stub answers the second call with a 500.
        long conversation = conversation();

        List<TurnStream.Event> events = onPriced(conversation);

        assertThat(PRICED.bodies()).as("the answered call and the 500").hasSize(2);
        assertEndedShort(events, conversation, "MODEL", 1000, 100, "0.003");
    }

    /** ISC-520, a timeout: the connection's request timeout is 2 s and the second call never answers in time. */
    @Test
    void aTurnWhoseSecondCallTimesOutStoresTheFirst() {
        pricedSecond(line("(?m)^    timeout: \\$\\{CHAT_LLM_TIMEOUT.*$", "    timeout: PT2S"));
        PRICED.enqueue(ModelStub.toolCalls("profile", "{}").withUsage(1000, 100));
        PRICED.enqueue(ModelStub.text(Duration.ofSeconds(10), "Too late.").withUsage(9, 9));
        long conversation = conversation();

        List<TurnStream.Event> events = onPriced(conversation);

        assertThat(PRICED.bodies())
                .as("the answered call and the one that timed out")
                .hasSize(2);
        assertEndedShort(events, conversation, "MODEL", 1000, 100, "0.003");
    }

    /**
     * ISC-520, the operator's stop, in the middle of the second call's stream. The cut call never
     * delivers its usage chunk, which comes last, so the tokens stored are the first call's — which
     * the provider reported — and nothing for the call it never finished. That second call did
     * deliver part of its stream and may well have been billed, so the counts are a lower bound:
     * the turn stores no cost, as though they were exact, and the spend counts it as unreported —
     * never as unpriced, the model has a price.
     */
    @Test
    void aTurnStoppedMidStreamKeepsTheFirstCallsTokensWithoutACost() {
        pricedSecond(UnaryOperator.identity());
        int unpriced = spendCount("SECOND", "unpricedToday");
        int unreported = spendCount("SECOND", "unreportedToday");
        PRICED.enqueue(ModelStub.toolCalls("profile", "{}").withUsage(1000, 100));
        String[] parts = new String[20];
        for (int i = 0; i < parts.length; i++) {
            parts[i] = "c" + i + " ";
        }
        PRICED.enqueue(ModelStub.text(Duration.ofMillis(100), parts).withUsage(9, 9));
        long conversation = conversation();
        long[] turn = new long[1];
        int[] texts = new int[1];
        int[] stopStatus = new int[1];

        List<TurnStream.Event> events = TurnStream.post(
                port, "/api/v1/chat/conversations/" + conversation + "/turns", pricedQuestion(), event -> {
                    if (event.name().equals("turn")) {
                        turn[0] = Long.parseLong(TurnStream.JsonText.field(event.data(), "turnId"));
                    } else if (event.name().equals("text") && ++texts[0] == 2) {
                        stopStatus[0] = TurnStream.postStatus(
                                port, "/api/v1/chat/conversations/" + conversation + "/turns/" + turn[0] + "/stop");
                    }
                });

        assertThat(stopStatus[0]).isEqualTo(202);
        assertThat(TurnStream.names(events).getLast()).isEqualTo("done");
        JsonNode done = read(events.getLast().data());
        assertThat(done.path("state").asText()).isEqualTo("STOPPED");
        assertThat(List.of(
                        done.path("inputTokens").asInt(-1),
                        done.path("outputTokens").asInt(-1)))
                .as("on the stream")
                .containsExactly(1000, 100);
        assertThat(cost(done))
                .as("no cost on the stream: the second call's usage is missing")
                .isNull();
        assertThat(incomplete(done))
                .as("the stream marks the usage as not fully reported")
                .isTrue();
        assertThat(storedMarks(conversation)).as("and so does the reload").containsExactly(true);
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT state, input_tokens, output_tokens, cost_usd FROM chat_turn WHERE conversation_id = ?",
                conversation);
        assertThat(Arrays.asList(
                        row.get("state"), row.get("input_tokens"), row.get("output_tokens"), row.get("cost_usd")))
                .as("the row: the first call's tokens and no cost")
                .containsExactly("STOPPED", 1000, 100, null);
        assertThat(spendCount("SECOND", "unreportedToday"))
                .as("the status counts it as unreported")
                .isEqualTo(unreported + 1);
        assertThat(spendCount("SECOND", "unpricedToday"))
                .as("and never as unpriced: the model has a price")
                .isEqualTo(unpriced);
    }

    /**
     * A billed turn whose only call is cut before its usage chunk — which the OpenAI module sends
     * last — reported no tokens at all. It is stored as 0/0 with no cost, so the spend lists it as
     * unreported rather than dropping it: the provider may well have billed the cut call. The
     * stream's {@code done} says the same, 0/0 and no {@code costUsd}. Its model has a price, so it
     * is never counted as unpriced.
     */
    @Test
    void aBilledTurnCutBeforeAnyUsageIsListedAsUnreported() {
        pricedSecond(UnaryOperator.identity());
        String[] parts = new String[20];
        for (int i = 0; i < parts.length; i++) {
            parts[i] = "c" + i + " ";
        }
        PRICED.enqueue(ModelStub.text(Duration.ofMillis(100), parts).withUsage(9, 9));
        int unpriced = spendCount("SECOND", "unpricedToday");
        int unreported = spendCount("SECOND", "unreportedToday");
        long conversation = conversation();
        long[] turn = new long[1];
        int[] texts = new int[1];

        List<TurnStream.Event> events = TurnStream.post(
                port, "/api/v1/chat/conversations/" + conversation + "/turns", pricedQuestion(), event -> {
                    if (event.name().equals("turn")) {
                        turn[0] = Long.parseLong(TurnStream.JsonText.field(event.data(), "turnId"));
                    } else if (event.name().equals("text") && ++texts[0] == 2) {
                        TurnStream.postStatus(
                                port, "/api/v1/chat/conversations/" + conversation + "/turns/" + turn[0] + "/stop");
                    }
                });

        assertThat(TurnStream.names(events).getLast()).isEqualTo("done");
        JsonNode done = read(events.getLast().data());
        assertThat(done.path("state").asText()).isEqualTo("STOPPED");
        assertThat(List.of(
                        done.path("inputTokens").asInt(-1),
                        done.path("outputTokens").asInt(-1)))
                .as("0/0 on the stream")
                .containsExactly(0, 0);
        assertThat(done.has("costUsd")).as("no cost on the stream").isFalse();
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT input_tokens, output_tokens, cost_usd FROM chat_turn WHERE conversation_id = ?", conversation);
        assertThat(Arrays.asList(row.get("input_tokens"), row.get("output_tokens"), row.get("cost_usd")))
                .as("the row: 0/0 and no cost")
                .containsExactly(0, 0, null);
        JsonNode stored =
                get("/api/v1/chat/conversations/" + conversation).path("turns").get(0);
        assertThat(List.of(
                        stored.path("inputTokens").asInt(-1),
                        stored.path("outputTokens").asInt(-1)))
                .as("0/0 on reload")
                .containsExactly(0, 0);
        assertThat(stored.has("costUsd")).as("no cost on reload").isFalse();
        assertThat(Arrays.asList(incomplete(done), incomplete(stored)))
                .as("marked as not fully reported on the stream and on reload")
                .containsExactly(true, true);
        assertThat(spendCount("SECOND", "unreportedToday"))
                .as("the status counts it as unreported")
                .isEqualTo(unreported + 1);
        assertThat(spendCount("SECOND", "unpricedToday"))
                .as("and never as unpriced: the model has a price")
                .isEqualTo(unpriced);
    }

    /**
     * An unbilled turn whose call reported no usage has no data at all: no tokens and no cost. A
     * cost of 0 beside no tokens would claim a figure nobody reported.
     */
    @Test
    void anUnbilledTurnWithoutUsageStoresNoCost() {
        PRIMARY.enqueue(ModelStub.text(Duration.ZERO, "Free and silent."));
        long conversation = conversation();

        JsonNode done = done(TurnStream.ask(port, conversation, "Anything?"));

        assertThat(List.of(done.has("inputTokens"), done.has("outputTokens"), done.has("costUsd")))
                .as("no counts and no cost on the stream")
                .containsExactly(false, false, false);
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT input_tokens, output_tokens, cost_usd FROM chat_turn WHERE conversation_id = ?", conversation);
        assertThat(Arrays.asList(row.get("input_tokens"), row.get("output_tokens"), row.get("cost_usd")))
                .as("the row: no data, all three null")
                .containsExactly(null, null, null);
    }

    /**
     * A billed turn the process died under while the model was speaking: usage comes last, so its
     * row holds text and no tokens. The next start's sweep ends it, and the spend must not lose it
     * — the provider billed the words it streamed — so it is stored as 0/0 without a cost and
     * counted as unreported. A billed turn that never spoke, and an unbilled one that did, add
     * nothing.
     */
    @Test
    void aSweptBilledTurnThatSpokeIsCountedAsUnreported() {
        pricedSecond(UnaryOperator.identity());
        int unpriced = spendCount("SECOND", "unpricedToday");
        int unreported = spendCount("SECOND", "unreportedToday");
        int primary = spendCount("PRIMARY", "unreportedToday");
        long conversation = conversation();
        long spoke = streaming(conversation, 1, "SECOND", "Half an ans");
        long silent = streaming(conversation, 2, "SECOND", "");
        long free = streaming(conversation, 3, "PRIMARY", "Local words");

        int swept = turns.sweep(jdbc.queryForObject("SELECT now() + interval '1 second'", OffsetDateTime.class));

        assertThat(swept).as("all three ended").isEqualTo(3);
        assertThat(jdbc.queryForList(
                        "SELECT coalesce(input_tokens::text, 'null') || '/' || coalesce(output_tokens::text, 'null')"
                                + " || ' ' || coalesce(cost_usd::text, 'null') FROM chat_turn WHERE id IN (?, ?, ?)"
                                + " ORDER BY ordinal",
                        String.class,
                        spoke,
                        silent,
                        free))
                .as("the billed one that spoke at 0/0 without a cost, the others without data")
                .containsExactly("0/0 null", "null/null null", "null/null null");
        assertThat(spendCount("SECOND", "unreportedToday"))
                .as("the billed turn that spoke is counted as unreported")
                .isEqualTo(unreported + 1);
        assertThat(spendCount("SECOND", "unpricedToday"))
                .as("never as unpriced")
                .isEqualTo(unpriced);
        assertThat(spendCount("PRIMARY", "unreportedToday"))
                .as("an unbilled turn counts nowhere")
                .isEqualTo(primary);
        assertThat(storedMarks(conversation))
                .as("only the billed turn that spoke reloads as not fully reported")
                .containsExactly(true, null, null);
    }

    /** A turn row left at STREAMING, as a process that died mid-answer leaves it. */
    private long streaming(long conversation, int ordinal, String connection, String text) {
        return jdbc.queryForObject(
                "INSERT INTO chat_turn (conversation_id, ordinal, question, state, connection, model, answer_md)"
                        + " VALUES (?, ?, 'q', 'STREAMING', ?, 'm', ?) RETURNING id",
                Long.class,
                conversation,
                ordinal,
                connection,
                text);
    }

    /**
     * A billed, priced call the provider refuses before any chunk — a wrong key — never reached a
     * model that could bill it. The turn keeps no counts and no cost, its {@code error} names none,
     * and the spend does not count it as not priced: there is nothing it could have cost.
     */
    @Test
    void aBilledCallRefusedBeforeAnyChunkStoresNoUsage() {
        pricedSecond(UnaryOperator.identity());
        PRICED.enqueue(ModelStub.refused(401));
        int before = spendCount("SECOND", "unpricedToday");
        int unreported = spendCount("SECOND", "unreportedToday");
        long conversation = conversation();

        List<TurnStream.Event> events = onPriced(conversation);

        assertThat(TurnStream.names(events).getLast()).isEqualTo("error");
        JsonNode error = read(events.getLast().data());
        assertThat(error.path("reason").asText()).isEqualTo("MODEL");
        assertThat(List.of(error.has("inputTokens"), error.has("outputTokens"), error.has("costUsd")))
                .as("no counts and no cost on the error")
                .containsExactly(false, false, false);
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT input_tokens, output_tokens, cost_usd FROM chat_turn WHERE conversation_id = ?", conversation);
        assertThat(Arrays.asList(row.get("input_tokens"), row.get("output_tokens"), row.get("cost_usd")))
                .as("the row: no counts and no cost")
                .containsExactly(null, null, null);
        assertThat(spendCount("SECOND", "unpricedToday"))
                .as("the status does not count it as not priced")
                .isEqualTo(before);
        assertThat(spendCount("SECOND", "unreportedToday"))
                .as("nor as unreported")
                .isEqualTo(unreported);
    }

    /** One of the status's counts for {@code connection}, such as {@code unpricedToday}; 0 when absent. */
    private int spendCount(String connection, String field) {
        for (JsonNode row : get("/api/v1/chat/status").path("spend").path("connections")) {
            if (row.path("name").asText().equals(connection)) {
                return row.path(field).asInt();
            }
        }
        return 0;
    }

    /**
     * A turn that ended short with {@code reason}: its {@code error} names it and carries the
     * turn's tokens and cost as the row stores them, so the live popover needs no reload, and the
     * row and the reloaded turn carry the tokens and the cost of the calls the stub reported.
     */
    private void assertEndedShort(
            List<TurnStream.Event> events, long conversation, String reason, int input, int output, String cost) {
        assertThat(TurnStream.names(events).getLast()).isEqualTo("error");
        JsonNode error = read(events.getLast().data());
        assertThat(error.path("reason").asText()).isEqualTo(reason);
        assertThat(List.of(
                        error.path("inputTokens").asInt(-1),
                        error.path("outputTokens").asInt(-1)))
                .as("the counts on the error")
                .containsExactly(input, output);
        assertThat(cost(error)).as("the cost on the error").isEqualTo(cost);
        assertRow(conversation, "INCOMPLETE", reason, input, output, cost);
    }

    /** The conversation's one row and its reloaded turn: state, reason, both counts and the cost. */
    private void assertRow(long conversation, String state, String reason, int input, int output, String cost) {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT state, end_reason, input_tokens, output_tokens, cost_usd FROM chat_turn"
                        + " WHERE conversation_id = ?",
                conversation);
        assertThat(row.get("state")).as("the row's state").isEqualTo(state);
        assertThat(row.get("end_reason")).as("the row's end_reason").isEqualTo(reason);
        // Arrays.asList, not List.of: a count that was never written is null, and the assertion
        // should say so rather than fail with a NullPointerException of its own.
        assertThat(Arrays.asList(row.get("input_tokens"), row.get("output_tokens")))
                .as("the row's counts")
                .containsExactly(input, output);
        assertThat((BigDecimal) row.get("cost_usd"))
                .as("the row's cost")
                .isNotNull()
                .isEqualByComparingTo(cost);

        JsonNode stored =
                get("/api/v1/chat/conversations/" + conversation).path("turns").get(0);
        assertThat(List.of(
                        stored.path("inputTokens").asInt(-1),
                        stored.path("outputTokens").asInt(-1)))
                .as("on reload")
                .containsExactly(input, output);
        assertThat(cost(stored)).as("the cost on reload").isEqualTo(cost);
    }

    private List<TurnStream.Event> onPriced(long conversation) {
        return TurnStream.post(
                port, "/api/v1/chat/conversations/" + conversation + "/turns", pricedQuestion(), event -> {});
    }

    private static String pricedQuestion() {
        return "{\"question\":\"Read my profile.\",\"connection\":\"SECOND\",\"model\":\"" + PRICED_MODEL + "\"}";
    }

    /**
     * The shipped files with the primary at its stub and {@code chat.connection} at {@link #PRICED}
     * as a billed {@code openai-compatible} endpoint, {@link #PRICED_MODEL} priced, then {@code edit}.
     */
    private void pricedSecond(UnaryOperator<String> edit) {
        ConfigFixtures.materialize(CONFIG_DIRECTORY);
        Path pipeline = CONFIG_DIRECTORY.resolve("pipeline.yaml");
        try {
            String yaml = ModelStub.pointSecondAt(
                    ModelStub.pointLlmAt(Files.readString(pipeline, StandardCharsets.UTF_8), PRIMARY.baseUrl()),
                    "openai-compatible",
                    PRICED.baseUrl());
            String prices = "  prices:\n    - connection: SECOND\n      model: " + PRICED_MODEL
                    + "\n      input: 2\n      output: 10";
            yaml = line("(?m)^  prices: \\[]$", prices)
                    .andThen(line("(?m)^    billed: \\$\\{CHAT_LLM_BILLED:}$", "    billed: true"))
                    .andThen(edit)
                    .apply(yaml);
            Files.writeString(pipeline, yaml, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        config.reload();
    }

    /** Replaces the first line {@code regex} matches with {@code replacement}, failing when none does. */
    private static UnaryOperator<String> line(String regex, String replacement) {
        return text -> {
            String after = text.replaceFirst(regex, java.util.regex.Matcher.quoteReplacement(replacement));
            assertThat(after).as("the rewrite of " + regex + " matched").isNotEqualTo(text);
            return after;
        };
    }

    private List<TurnStream.Event> onSecond(long conversation, String model) {
        return TurnStream.post(
                port,
                "/api/v1/chat/conversations/" + conversation + "/turns",
                "{\"question\":\"What does it cost?\",\"connection\":\"SECOND\",\"model\":\"" + model + "\"}",
                event -> {});
    }

    private static JsonNode done(List<TurnStream.Event> events) {
        assertThat(TurnStream.names(events).getLast()).isEqualTo("done");
        JsonNode done = read(events.getLast().data());
        assertThat(done.path("state").asText()).isEqualTo("DONE");
        return done;
    }

    /** A turn's or a {@code done}'s {@code costUsd}, or null where the field is left out. */
    private static String cost(JsonNode node) {
        return node.has("costUsd") ? node.path("costUsd").asText() : null;
    }

    /** A turn's or an ending event's {@code usageIncomplete}, or null where the field is left out. */
    private static Boolean incomplete(JsonNode node) {
        return node.has("usageIncomplete") ? node.path("usageIncomplete").asBoolean() : null;
    }

    /** Each reloaded turn's {@code usageIncomplete} in order, null where it is left out. */
    private List<Boolean> storedMarks(long conversation) {
        List<Boolean> marks = new ArrayList<>();
        get("/api/v1/chat/conversations/" + conversation).path("turns").forEach(turn -> marks.add(incomplete(turn)));
        return marks;
    }

    /** Each reloaded turn's {@code costUsd} in order. */
    private List<String> storedCosts(long conversation) {
        List<String> costs = new ArrayList<>();
        get("/api/v1/chat/conversations/" + conversation).path("turns").forEach(turn -> costs.add(cost(turn)));
        return costs;
    }

    /** Each row's {@code cost_usd} as Postgres writes it, {@code "null"} where none is stored. */
    private List<String> rowCosts(long conversation) {
        return jdbc.queryForList(
                "SELECT coalesce(cost_usd::text, 'null') FROM chat_turn WHERE conversation_id = ? ORDER BY ordinal",
                String.class,
                conversation);
    }

    /** The configuration of {@link #reset} with {@code entries} as the chat's price table, reloaded. */
    private void prices(String entries) {
        secondConnection("anthropic", HOSTED.baseUrl(), entries);
    }

    /** The same totals and cost on the stream's {@code done}, on the reloaded turn and on the row. */
    private void assertTotals(List<TurnStream.Event> events, long conversation, int input, int output, String cost) {
        JsonNode done = read(events.getLast().data());
        assertThat(done.path("state").asText()).isEqualTo("DONE");
        assertThat(done.path("inputTokens").isInt())
                .as("done carries inputTokens")
                .isTrue();
        assertThat(List.of(
                        done.path("inputTokens").asInt(),
                        done.path("outputTokens").asInt()))
                .as("on the stream")
                .containsExactly(input, output);
        assertThat(cost(done)).as("the cost on the stream").isEqualTo(cost);

        JsonNode stored =
                get("/api/v1/chat/conversations/" + conversation).path("turns").get(0);
        assertThat(List.of(
                        stored.path("inputTokens").asInt(-1),
                        stored.path("outputTokens").asInt(-1)))
                .as("on reload")
                .containsExactly(input, output);
        assertThat(cost(stored)).as("the cost on reload").isEqualTo(cost);

        assertThat(jdbc.queryForList(
                        "SELECT input_tokens || ' ' || output_tokens FROM chat_turn WHERE conversation_id = ?",
                        String.class,
                        conversation))
                .as("on the row")
                .containsExactly(input + " " + output);
    }

    private static JsonNode read(String json) {
        try {
            return JSON.readTree(json);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private long conversation() {
        return jdbc.queryForObject("INSERT INTO chat_conversation (title) VALUES ('') RETURNING id", Long.class);
    }

    private JsonNode get(String path) {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as(path).isEqualTo(200);
            return JSON.readTree(response.body());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** The shipped files, the primary pointed at its stub and {@code chat.connection} at {@code baseUrl}. */
    private void secondConnection(String provider, String baseUrl) {
        secondConnection(provider, baseUrl, null);
    }

    /** The same, with {@code prices} in place of the shipped empty table when it is not null. */
    private void secondConnection(String provider, String baseUrl, String prices) {
        ConfigFixtures.materialize(CONFIG_DIRECTORY);
        Path pipeline = CONFIG_DIRECTORY.resolve("pipeline.yaml");
        try {
            String shipped = Files.readString(pipeline, StandardCharsets.UTF_8);
            if (prices != null) {
                assertThat(shipped).as("the shipped price table").contains(SHIPPED_PRICES);
                shipped = shipped.replace(SHIPPED_PRICES, "  prices:\n" + prices);
            }
            Files.writeString(
                    pipeline,
                    ModelStub.pointSecondAt(ModelStub.pointLlmAt(shipped, PRIMARY.baseUrl()), provider, baseUrl),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        config.reload();
    }

    private static Path freshConfigDirectory() {
        try {
            Path dir = Files.createTempDirectory("leadgen-chat-turn-usage");
            dir.toFile().deleteOnExit();
            Path materialized = ConfigFixtures.materialize(dir);
            Path pipeline = materialized.resolve("pipeline.yaml");
            Files.writeString(
                    pipeline,
                    ModelStub.pointLlmAt(Files.readString(pipeline, StandardCharsets.UTF_8), PRIMARY.baseUrl()),
                    StandardCharsets.UTF_8);
            return materialized;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
