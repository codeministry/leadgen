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
import de.codeministry.leadgen.chat.suggest.SuggestionWarmup;
import de.codeministry.leadgen.config.ConfigFixtures;
import de.codeministry.leadgen.config.ConfigRegistry;
import de.codeministry.leadgen.config.Secrets;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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
 * ISC-507: a turn is answered by exactly the model and connection it was asked with, and a turn
 * asked without one by the default on the primary connection.
 *
 * <p>Two stubs, one per connection, each counting what it received, so "answered at the second
 * stub only" is read off the wire rather than off a row. The {@code turn} event and the reloaded
 * conversation are read over real HTTP, as the drawer reads them. One case puts a Messages-API
 * stub behind the second connection, because that kind builds a different client.
 *
 * <p>ISC-508: the model may change between turns, and the next one reads the conversation as the
 * earlier turns left it. ISC-509: a model that fails, times out or is refused ends its turn there,
 * and the other connection never hears of it — counted at the other stub, in both directions.
 *
 * <p>ISC-513: the suggestions, the follow-ups and the semantic tool's query vector stay on the
 * primary connection whichever model a turn uses.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ChatModelSelectTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final ModelStub PRIMARY = ModelStub.start();
    private static final ModelStub SECOND = ModelStub.start();
    private static final AnthropicStub HOSTED = AnthropicStub.start();

    /** A secret property the masker knows, planted in an offer a tool returns, as {@code ChatRedactionTest} does. */
    private static final String SECRET = "Xv9-select-Secret-Value";

    /** The embedding model one case switches retrieval on over, on the primary connection. */
    private static final String EMBEDDING = "test-embed";

    /** Created once, never inside the property supplier — see {@code LlmBudgetTest}. */
    private static final Path CONFIG_DIRECTORY = freshConfigDirectory();

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = Databases.postgres();

    @DynamicPropertySource
    static void configuration(DynamicPropertyRegistry registry) {
        registry.add("leadgen.config-dir", CONFIG_DIRECTORY::toString);
        registry.add("leadgen.test.client-secret", () -> SECRET);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ConfigRegistry config;

    @Autowired
    private SuggestionWarmup warmup;

    @AfterAll
    static void stop() {
        PRIMARY.close();
        SECOND.close();
        HOSTED.close();
    }

    @BeforeEach
    void reset() {
        PRIMARY.reset();
        SECOND.reset();
        HOSTED.reset();
        PRIMARY.lists(ModelStub.MODEL, "model-a");
        SECOND.lists("model-b");
        jdbc.update("DELETE FROM chat_conversation");
        jdbc.update("DELETE FROM chat_call_budget");
        jdbc.update("DELETE FROM chat_connection_call_budget");
        secondConnection("openai-compatible", SECOND.baseUrl());
    }

    @Test
    void aPickedModelIsAnsweredOnItsConnectionAndNoPickOnThePrimaryWithTheDefault() {
        assertThat(get("/api/v1/chat/models").path("connections")).hasSize(2);
        long conversation = conversation();

        SECOND.enqueue(ModelStub.text(Duration.ZERO, "From the second."));
        List<TurnStream.Event> picked = TurnStream.post(
                port,
                "/api/v1/chat/conversations/" + conversation + "/turns",
                "{\"question\":\"Which connection?\",\"connection\":\"SECOND\",\"model\":\"model-b\"}",
                event -> {});

        assertThat(TurnStream.names(picked).getLast()).isEqualTo("done");
        assertThat(TurnStream.text(picked)).isEqualTo("From the second.");
        assertThat(SECOND.bodies())
                .singleElement()
                .satisfies(body ->
                        assertThat(JSON.readTree(body).path("model").asText()).isEqualTo("model-b"));
        assertThat(PRIMARY.bodies()).as("nothing asked at the primary").isEmpty();
        assertThat(turnEvent(picked)).containsExactly("model-b", "SECOND");

        PRIMARY.enqueue(ModelStub.text(Duration.ZERO, "From the primary."));
        List<TurnStream.Event> unpicked = TurnStream.ask(port, conversation, "And by default?");

        assertThat(TurnStream.names(unpicked).getLast()).isEqualTo("done");
        assertThat(TurnStream.text(unpicked)).isEqualTo("From the primary.");
        assertThat(PRIMARY.bodies())
                .singleElement()
                .satisfies(body ->
                        assertThat(JSON.readTree(body).path("model").asText()).isEqualTo(ModelStub.MODEL));
        assertThat(SECOND.bodies()).as("the second asked once, by the pick").hasSize(1);
        assertThat(turnEvent(unpicked)).containsExactly(ModelStub.MODEL, "PRIMARY");

        JsonNode turns = get("/api/v1/chat/conversations/" + conversation).path("turns");
        assertThat(turns).hasSize(2);
        assertThat(List.of(
                        turns.get(0).path("model").asText(),
                        turns.get(0).path("connection").asText()))
                .containsExactly("model-b", "SECOND");
        assertThat(List.of(
                        turns.get(1).path("model").asText(),
                        turns.get(1).path("connection").asText()))
                .containsExactly(ModelStub.MODEL, "PRIMARY");
    }

    /**
     * The second connection as the Messages API. No list is read before the turn: the controller
     * reads it once itself rather than refusing a pick it simply had not looked up yet.
     */
    @Test
    void aPickOnAMessagesApiSecondConnectionIsAnsweredThere() {
        secondConnection("anthropic", HOSTED.baseUrl());
        HOSTED.lists("model-b");
        HOSTED.enqueueText("From the hosted ", "model.");
        long conversation = conversation();

        List<TurnStream.Event> events = TurnStream.post(
                port,
                "/api/v1/chat/conversations/" + conversation + "/turns",
                "{\"question\":\"Which connection?\",\"connection\":\"SECOND\",\"model\":\"model-b\"}",
                event -> {});

        assertThat(TurnStream.names(events).getLast()).isEqualTo("done");
        assertThat(TurnStream.text(events)).isEqualTo("From the hosted model.");
        assertThat(HOSTED.listings()).as("the list read once, by the check").isEqualTo(1);
        assertThat(HOSTED.paths()).containsExactly("/v1/messages");
        assertThat(HOSTED.bodies())
                .singleElement()
                .satisfies(body ->
                        assertThat(JSON.readTree(body).path("model").asText()).isEqualTo("model-b"));
        assertThat(PRIMARY.bodies()).isEmpty();
        assertThat(SECOND.bodies()).isEmpty();
        assertThat(turnEvent(events)).containsExactly("model-b", "SECOND");
        assertThat(jdbc.queryForList("SELECT model || ' ' || connection FROM chat_turn", String.class))
                .containsExactly("model-b SECOND");
    }

    /**
     * ISC-508: turn 1 on the primary with a tool round and a citation, turn 2 on the second
     * connection, then turn 2 regenerated on the primary. The second connection reads the
     * conversation as turn 1 left it — the question, the answer with its citation as a marker, no
     * planted secret — and the regenerate is a turn of its own, naming the primary's model, while
     * the replaced turn keeps the second's.
     */
    @Test
    void theNextModelReadsTheConversationAsTheEarlierTurnsLeftItAndARegenerateKeepsTheReplaced() {
        long offer = plantedOffer();
        long conversation = conversation();
        PRIMARY.enqueue(ModelStub.toolCalls("search_offers", "{\"text\":\"kafka\"}"));
        PRIMARY.enqueue(ModelStub.text(Duration.ZERO, "The Kafka role ", "[[offer:" + offer + "]]."));

        List<TurnStream.Event> first = TurnStream.ask(port, conversation, "Which Kafka roles are open?");

        assertThat(TurnStream.names(first).getLast()).isEqualTo("done");
        assertThat(PRIMARY.bodies()).hasSize(2);
        assertThat(PRIMARY.bodies().getLast())
                .as("turn 1's tool result reached the primary masked")
                .contains(Secrets.MASK)
                .doesNotContain(SECRET);

        SECOND.enqueue(ModelStub.text(Duration.ZERO, "From the second."));
        List<TurnStream.Event> second = TurnStream.post(
                port,
                "/api/v1/chat/conversations/" + conversation + "/turns",
                "{\"question\":\"And the rate?\",\"connection\":\"SECOND\",\"model\":\"model-b\"}",
                event -> {});

        assertThat(TurnStream.names(second).getLast()).isEqualTo("done");
        String body = SECOND.bodies().getFirst();
        assertThat(SECOND.bodies()).hasSize(1);
        assertThat(roles(body)).containsExactly("system", "user", "assistant", "user");
        assertThat(contents(body).subList(1, 4))
                .containsExactly(
                        "Which Kafka roles are open?", "The Kafka role [[offer:" + offer + "]].", "And the rate?");
        assertThat(body).as("no planted secret in the history").doesNotContain(SECRET);
        long replaced = Long.parseLong(TurnStream.JsonText.field(turn(second).data(), "turnId"));

        PRIMARY.enqueue(ModelStub.text(Duration.ZERO, "From the primary again."));
        List<TurnStream.Event> again = TurnStream.post(
                port,
                "/api/v1/chat/conversations/" + conversation + "/turns/" + replaced + "/regenerate",
                "{\"connection\":\"PRIMARY\",\"model\":\"" + ModelStub.MODEL + "\"}",
                event -> {});

        assertThat(TurnStream.names(again).getLast()).isEqualTo("done");
        assertThat(turnEvent(again)).containsExactly(ModelStub.MODEL, "PRIMARY");
        assertThat(PRIMARY.bodies()).hasSize(3);
        assertThat(contents(PRIMARY.bodies().getLast()).subList(1, 4))
                .as("the regenerate reads turn 1 as it was left")
                .containsExactly(
                        "Which Kafka roles are open?", "The Kafka role [[offer:" + offer + "]].", "And the rate?");
        long regenerated = Long.parseLong(TurnStream.JsonText.field(turn(again).data(), "turnId"));
        assertThat(regenerated).isNotEqualTo(replaced);
        assertThat(jdbc.queryForList(
                        "SELECT id || ' ' || model || ' ' || connection || ' ' || coalesce(replaces_turn_id::text, '-')"
                                + " FROM chat_turn WHERE conversation_id = ? ORDER BY id",
                        String.class,
                        conversation))
                .as("three turns; the replaced one still names the second")
                .hasSize(3)
                .endsWith(replaced + " model-b SECOND -", regenerated + " " + ModelStub.MODEL + " PRIMARY " + replaced);
    }

    /**
     * ISC-509: a turn on the second connection whose model answers 500, then times out, then is
     * refused by its own ceiling, ends with {@code MODEL}, {@code MODEL} and {@code BUDGET} — and
     * never asks the primary.
     */
    @Test
    void aFailingSecondConnectionEndsItsTurnAndNeverFallsBackToThePrimary() {
        timeouts("PT2S");

        List<String> reasons = new ArrayList<>();
        // No reply queued: the stub answers 500.
        reasons.add(failed(pickedTurn("SECOND", "model-b")));
        SECOND.enqueue(ModelStub.text(Duration.ofSeconds(10), "Too late."));
        reasons.add(failed(pickedTurn("SECOND", "model-b")));
        secondCeiling("0");
        reasons.add(failed(pickedTurn("SECOND", "model-b")));

        assertThat(reasons).containsExactly("MODEL", "MODEL", "BUDGET");
        assertThat(SECOND.bodies())
                .as("the 500 and the timeout, nothing once refused")
                .hasSize(2);
        assertThat(PRIMARY.bodies()).as("requests at the primary stub").isEmpty();
        assertThat(jdbc.queryForList("SELECT state FROM chat_turn", String.class))
                .containsExactly("INCOMPLETE", "INCOMPLETE", "INCOMPLETE");
    }

    /** ISC-509, swapped: the same three failures on the primary never reach the second connection. */
    @Test
    void aFailingPrimaryEndsItsTurnAndNeverFallsBackToTheSecondConnection() {
        timeouts("PT2S");

        List<String> reasons = new ArrayList<>();
        reasons.add(failed(pickedTurn("PRIMARY", ModelStub.MODEL)));
        PRIMARY.enqueue(ModelStub.text(Duration.ofSeconds(10), "Too late."));
        reasons.add(failed(pickedTurn("PRIMARY", ModelStub.MODEL)));
        rewrite(text -> text.replaceFirst("(?m)^  max_calls_per_day: .*$", "  max_calls_per_day: 0"));
        reasons.add(failed(pickedTurn("PRIMARY", ModelStub.MODEL)));

        assertThat(reasons).containsExactly("MODEL", "MODEL", "BUDGET");
        assertThat(PRIMARY.bodies())
                .as("the 500 and the timeout, nothing once refused")
                .hasSize(2);
        assertThat(SECOND.bodies()).as("requests at the second stub").isEmpty();
    }

    /**
     * ISC-513: a turn on the second connection that calls {@code search_by_meaning}, then its
     * follow-ups and the empty chat's suggestions. Retrieval is switched on over an embedding model
     * on the primary, so the tool really asks for a query vector; the primary stub answers that
     * request 500, which the tool reports as unreadable words — what is counted is where it went.
     * Everything but the turn's own two rounds arrives at the primary stub, nothing else at the
     * second.
     */
    @Test
    void suggestionsFollowUpsAndTheQueryVectorStayOnThePrimaryWhicheverModelATurnUses() {
        rewrite(text -> text.replaceFirst(
                        "(?m)^(\\s*)embedding: \\$\\{LLM_MODEL_EMBEDDING}.*$", "$1embedding: " + EMBEDDING)
                // By its placeholder: `enabled:` appears on several blocks in this file.
                .replace("${RETRIEVAL_ENABLED:false}", "true"));
        long offer = plantedOffer();
        long conversation = conversation();
        SECOND.enqueue(ModelStub.toolCalls(
                "search_by_meaning", "{\"query\":\"event streaming\"}", "search_offers", "{\"text\":\"kafka\"}"));
        SECOND.enqueue(ModelStub.text(Duration.ZERO, "The Kafka role ", "[[offer:" + offer + "]]."));

        List<TurnStream.Event> events = TurnStream.post(
                port,
                "/api/v1/chat/conversations/" + conversation + "/turns",
                "{\"question\":\"Anything like event streaming?\",\"connection\":\"SECOND\",\"model\":\"model-b\"}",
                event -> {});

        assertThat(PRIMARY.bodies())
                .as("the query vector, asked at the primary")
                .singleElement()
                .satisfies(body -> {
                    JsonNode request = JSON.readTree(body);
                    assertThat(request.path("model").asText()).isEqualTo(EMBEDDING);
                    assertThat(request.path("input").toString()).contains("event streaming");
                });
        assertThat(SECOND.bodies())
                .as("the turn's two rounds and nothing else at the second")
                .hasSize(2)
                .allSatisfy(body ->
                        assertThat(JSON.readTree(body).path("model").asText()).isEqualTo("model-b"));
        assertThat(TurnStream.names(events).getLast()).isEqualTo("done");
        long turn = Long.parseLong(TurnStream.JsonText.field(turn(events).data(), "turnId"));

        PRIMARY.enqueue(ModelStub.text(Duration.ZERO, "Phrased."));
        PRIMARY.enqueue(ModelStub.text(Duration.ZERO, "Phrased."));
        assertThat(get("/api/v1/chat/conversations/" + conversation + "/turns/" + turn + "/followups"))
                .hasSizeGreaterThanOrEqualTo(2);
        assertThat(get("/api/v1/chat/suggestions?context=o:" + offer)).isNotEmpty();
        // Since spec 027 the suggestions are phrased in the background, after the response.
        awaitWarmup();

        assertThat(PRIMARY.bodies())
                .as("the vector, then the follow-ups and the suggestions phrased at the primary")
                .hasSize(3);
        assertThat(PRIMARY.bodies().subList(1, 3))
                .allSatisfy(body ->
                        assertThat(JSON.readTree(body).path("model").asText()).isEqualTo(ModelStub.MODEL));
        assertThat(SECOND.bodies()).as("still the turn's two rounds alone").hasSize(2);
    }

    private void awaitWarmup() {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (!warmup.idle()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("the suggestion warm-up did not finish within 30 s");
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }

    /** One turn in a fresh conversation, asked of {@code model} on {@code connection}. */
    private List<TurnStream.Event> pickedTurn(String connection, String model) {
        return TurnStream.post(
                port,
                "/api/v1/chat/conversations/" + conversation() + "/turns",
                "{\"question\":\"Anything?\",\"connection\":\"" + connection + "\",\"model\":\"" + model + "\"}",
                event -> {});
    }

    /** The reason of the turn's {@code error} event; fails when the turn ended any other way. */
    private static String failed(List<TurnStream.Event> events) {
        TurnStream.Event last = events.getLast();
        assertThat(last.name()).as("the turn's last event").isEqualTo("error");
        return TurnStream.JsonText.field(last.data(), "reason");
    }

    /** An offer {@code search_offers} finds for "kafka", carrying the planted secret in its title and text. */
    private long plantedOffer() {
        long source = jdbc.queryForObject(
                "INSERT INTO source (name, kind) VALUES ('select', 'file') ON CONFLICT (name) DO UPDATE"
                        + " SET kind = excluded.kind RETURNING id",
                Long.class);
        jdbc.update("DELETE FROM offer WHERE external_id = 'select-1'");
        String planted = "Ask for " + SECRET;
        return jdbc.queryForObject("""
                INSERT INTO offer (source_id, external_id, title, description, url, fingerprint, status,
                                   score_value, portal, ingested_at)
                VALUES (?, 'select-1', ?, ?, 'https://example.invalid/select', 'select-1', 'PASSED', 80,
                        'portal-a', now())
                RETURNING id
                """, Long.class, source, "Kafka Developer · " + planted, planted);
    }

    /** The {@code role} of each message in a request body. */
    private static List<String> roles(String body) {
        return messages(body).stream()
                .map(message -> message.path("role").asText())
                .toList();
    }

    /** The {@code content} of each message in a request body. */
    private static List<String> contents(String body) {
        return messages(body).stream()
                .map(message -> message.path("content").asText())
                .toList();
    }

    private static List<JsonNode> messages(String body) {
        try {
            List<JsonNode> messages = new ArrayList<>();
            JSON.readTree(body).path("messages").forEach(messages::add);
            return messages;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Both connections' request timeout, so a model that never answers fails in seconds. */
    private void timeouts(String timeout) {
        rewrite(text -> text.replaceFirst("(?m)^  timeout: .*$", "  timeout: " + timeout)
                .replaceFirst("(?m)^    timeout: \\$\\{CHAT_LLM_TIMEOUT.*$", "    timeout: " + timeout));
    }

    /** {@code chat.connection.max_calls_per_day}. */
    private void secondCeiling(String value) {
        rewrite(text -> text.replaceFirst(
                "(?m)^    max_calls_per_day: \\$\\{CHAT_LLM_MAX_CALLS_PER_DAY.*$", "    max_calls_per_day: " + value));
    }

    private void rewrite(UnaryOperator<String> edit) {
        Path pipeline = CONFIG_DIRECTORY.resolve("pipeline.yaml");
        try {
            String before = Files.readString(pipeline, StandardCharsets.UTF_8);
            String after = edit.apply(before);
            assertThat(after).as("the rewrite matched").isNotEqualTo(before);
            Files.writeString(pipeline, after, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        config.reload();
    }

    /** The {@code turn} event. */
    private static TurnStream.Event turn(List<TurnStream.Event> events) {
        return events.stream()
                .filter(event -> event.name().equals("turn"))
                .findFirst()
                .orElseThrow();
    }

    /** The {@code turn} event's model and connection. */
    private static List<String> turnEvent(List<TurnStream.Event> events) {
        TurnStream.Event turn = events.stream()
                .filter(event -> event.name().equals("turn"))
                .findFirst()
                .orElseThrow();
        return List.of(
                TurnStream.JsonText.field(turn.data(), "model"), TurnStream.JsonText.field(turn.data(), "connection"));
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
        ConfigFixtures.materialize(CONFIG_DIRECTORY);
        Path pipeline = CONFIG_DIRECTORY.resolve("pipeline.yaml");
        try {
            String shipped = Files.readString(pipeline, StandardCharsets.UTF_8);
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
            Path dir = Files.createTempDirectory("leadgen-chat-model-select");
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
