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
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
 * A turn's time, over real HTTP: the deadline that stops a turn rather than only its stream, and
 * the heartbeat that keeps a proxy from cutting a stream whose model is silent. Both run on short
 * test intervals set as properties, so the test does not wait out the shipped 15 minutes.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"leadgen.chat.turn-timeout=PT2S", "leadgen.chat.heartbeat=PT0.15S"})
@Testcontainers
class ChatTurnDeadlineTest {

    private static final ModelStub MODEL = ModelStub.start();

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
    private ChatTurnService turns;

    @AfterAll
    static void stop() {
        MODEL.close();
    }

    @BeforeEach
    void reset() {
        MODEL.reset();
        jdbc.update("DELETE FROM chat_conversation");
        jdbc.update("DELETE FROM chat_call_budget");
    }

    /**
     * Fix 3B-2: a turn past its deadline is stopped like a stop request — the model call cancelled,
     * the row INCOMPLETE, an error with the reason — and its pool thread is handed back, instead of
     * the loop outliving the stream the container already closed.
     */
    @Test
    void aTurnPastItsDeadlineIsStoppedAndFreesItsThread() throws Exception {
        long conversation = conversation();
        MODEL.enqueue(ModelStub.text(Duration.ofMillis(1500), "Slow ", "and ", "slower ", "still."));
        List<ChatEvent> events = new CopyOnWriteArrayList<>();
        CountDownLatch finished = new CountDownLatch(1);

        turns.start(conversation, "Anything?", events::add, finished::countDown);

        assertThat(finished.await(5, TimeUnit.SECONDS))
                .as("the turn's thread is handed back")
                .isTrue();
        assertThat(events.getLast())
                .isInstanceOfSatisfying(
                        ChatError.class, error -> assertThat(error.message()).contains("time"));
        assertThat(jdbc.queryForObject("SELECT state FROM chat_turn", String.class))
                .isEqualTo("INCOMPLETE");
        Instant until = Instant.now().plusSeconds(3);
        while (MODEL.brokenAt() == null && Instant.now().isBefore(until)) {
            Thread.onSpinWait();
        }
        assertThat(MODEL.brokenAt()).as("the model call was cancelled").isNotNull();
    }

    /**
     * Fix 3B-6: a model silent for several heartbeat intervals still gets its stream through — the
     * reader sees SSE comments in between, which a proxy counts as traffic, and the turn ends with
     * {@code done}.
     */
    @Test
    void aSilentModelGetsHeartbeatsAndTheStreamSurvives() {
        long conversation = conversation();
        MODEL.enqueue(ModelStub.text(Duration.ofMillis(500), "Late."));

        List<String> lines = lines("/api/v1/chat/conversations/" + conversation + "/turns", "{\"question\":\"Q?\"}");

        assertThat(lines.stream().filter(line -> line.startsWith(":")).count())
                .as("heartbeat comments while the model was silent")
                .isGreaterThanOrEqualTo(3);
        assertThat(lines).anyMatch(line -> line.matches("event:\\s*done"));
    }

    private long conversation() {
        return jdbc.queryForObject("INSERT INTO chat_conversation (title) VALUES ('') RETURNING id", Long.class);
    }

    /** The raw lines of a stream, comments included — {@link TurnStream} keeps only named events. */
    private List<String> lines(String path, String body) {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request, HttpResponse.BodyHandlers.ofLines())
                    .body()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
