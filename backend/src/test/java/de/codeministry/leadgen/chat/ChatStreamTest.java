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
import java.util.Map;
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
 * ISC-423: a turn streams. The answer's text reaches the client while the model is still
 * writing, the stream ends with one {@code sources} event and then {@code done}, and a model
 * that drops the line mid-answer ends the stream with an {@code error} while the partial answer
 * is kept and marked incomplete.
 *
 * <p>The model is a stub streaming five chunks 100 ms apart, so "before the model finished" is
 * a comparison of two clocks in one JVM with 400 ms between them, not a race.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ChatStreamTest {

    private static final ModelStub MODEL = ModelStub.start();

    /** Created once, never inside the property supplier — see {@code LlmBudgetTest}. */
    private static final Path CONFIG = MODEL.configuration();

    private static final Duration PAUSE = Duration.ofMillis(100);

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
    }

    @Test
    void theAnswerArrivesBeforeTheModelHasFinished() {
        MODEL.enqueue(ModelStub.text(PAUSE, "One ", "two ", "three ", "four ", "five."));
        long conversation = conversation();

        List<TurnStream.Event> events = TurnStream.ask(port, conversation, "How many?");

        TurnStream.Event firstText = events.stream()
                .filter(event -> event.name().equals("text"))
                .findFirst()
                .orElseThrow();
        assertThat(firstText.at()).isBefore(MODEL.lastChunkAt());
        assertThat(TurnStream.text(events)).isEqualTo("One two three four five.");

        List<String> names = TurnStream.names(events);
        assertThat(names.getFirst()).isEqualTo("turn");
        assertThat(names.stream().filter("sources"::equals)).hasSize(1);
        assertThat(names.subList(names.size() - 2, names.size())).containsExactly("sources", "done");

        Map<String, Object> turn = jdbc.queryForMap("SELECT state, answer_md, question FROM chat_turn");
        assertThat(turn)
                .containsEntry("state", "DONE")
                .containsEntry("answer_md", "One two three four five.")
                .containsEntry("question", "How many?");
        assertThat(jdbc.queryForObject("SELECT title FROM chat_conversation", String.class))
                .isEqualTo("How many?");
    }

    @Test
    void aModelThatDropsTheLineLeavesTheTurnIncompleteWithItsPartialAnswer() {
        MODEL.enqueue(ModelStub.brokenText(PAUSE, "Half ", "an answer"));
        long conversation = conversation();

        List<TurnStream.Event> events = TurnStream.ask(port, conversation, "Anything?");

        List<String> names = TurnStream.names(events);
        assertThat(names.getLast()).isEqualTo("error");
        assertThat(TurnStream.JsonText.field(events.getLast().data(), "reason")).isEqualTo("MODEL");
        assertThat(names).doesNotContain("done", "sources");
        assertThat(TurnStream.text(events)).isEqualTo("Half an answer");

        Map<String, Object> turn = jdbc.queryForMap("SELECT state, answer_md FROM chat_turn");
        assertThat(turn).containsEntry("state", "INCOMPLETE").containsEntry("answer_md", "Half an answer");
    }

    /** ISC-439, the server half: stop after the third chunk of twenty. */
    @Test
    void stopCancelsTheModelCallAndKeepsThePartialAnswerMarkedStopped() {
        String[] parts = new String[20];
        for (int i = 0; i < parts.length; i++) {
            parts[i] = "c" + i + " ";
        }
        MODEL.enqueue(ModelStub.text(PAUSE, parts));
        long conversation = conversation();
        long[] turn = new long[1];
        int[] texts = new int[1];
        int[] stopStatus = new int[1];
        java.time.Instant[] stoppedAt = new java.time.Instant[1];
        int[] textsAtStop = new int[1];

        List<TurnStream.Event> events = TurnStream.post(
                port, "/api/v1/chat/conversations/" + conversation + "/turns", "{\"question\":\"Twenty?\"}", event -> {
                    if (event.name().equals("turn")) {
                        turn[0] = Long.parseLong(TurnStream.JsonText.field(event.data(), "turnId"));
                    } else if (event.name().equals("text") && ++texts[0] == 3) {
                        stoppedAt[0] = java.time.Instant.now();
                        stopStatus[0] = TurnStream.postStatus(
                                port, "/api/v1/chat/conversations/" + conversation + "/turns/" + turn[0] + "/stop");
                        textsAtStop[0] = texts[0];
                    }
                });

        assertThat(stopStatus[0]).isEqualTo(202);
        assertThat(texts[0])
                .as("text events after the stop")
                .isEqualTo(textsAtStop[0])
                .isEqualTo(3);
        assertThat(events.getLast().name()).isEqualTo("done");
        assertThat(TurnStream.JsonText.field(events.getLast().data(), "state")).isEqualTo("STOPPED");
        // The stub notices a closed connection at its next write, up to two pauses later; the
        // probe's bound is one second from the stop, so that is how long it is given.
        java.time.Instant deadline = stoppedAt[0].plus(Duration.ofSeconds(1));
        while (MODEL.brokenAt() == null && java.time.Instant.now().isBefore(deadline)) {
            Thread.onSpinWait();
        }
        assertThat(MODEL.brokenAt()).as("the stub saw its connection closed").isNotNull();
        assertThat(Duration.between(stoppedAt[0], MODEL.brokenAt())).isLessThan(Duration.ofSeconds(1));
        assertThat(MODEL.lastChunkAt())
                .as("the stub never got to write its last chunk")
                .isNull();

        Map<String, Object> stored = jdbc.queryForMap("SELECT state, answer_md FROM chat_turn");
        assertThat(stored).containsEntry("state", "STOPPED").containsEntry("answer_md", "c0 c1 c2 ");
    }

    private long conversation() {
        return jdbc.queryForObject("INSERT INTO chat_conversation (title) VALUES ('') RETURNING id", Long.class);
    }
}
