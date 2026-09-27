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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;

import de.codeministry.leadgen.Databases;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Fix 5B-5, over real HTTP: a turn's deadline starts when a pool thread picks it up, so the time it
 * waited in the queue is no part of it. A stream whose container timeout was counted from the
 * request lost that wait out of its grace, and a turn that waited long enough had its stream closed
 * before its own terminal event — the reader saw a stream end with neither {@code done} nor
 * {@code error}.
 *
 * <p>The turn service is a stub that holds its turn back for longer than the old grace (the turn
 * timeout plus 30 s) before it ends it, exactly as a queued turn that starts late would.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"leadgen.chat.turn-timeout=PT0.5S", "leadgen.chat.heartbeat=PT1S"})
@Testcontainers
class ChatStreamQueueWaitTest {

    /** Past the old emitter timeout of turn timeout (0.5 s) plus the 30 s grace. */
    private static final Duration QUEUED = Duration.ofSeconds(32);

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

    @MockitoBean
    private ChatTurnService turns;

    @MockitoBean
    private ConversationRepository conversations;

    @MockitoBean
    private ChatCapability capability;

    @AfterAll
    static void stop() {
        MODEL.close();
    }

    @Test
    void aTurnThatWaitedInTheQueuePastTheGraceStillSendsItsTerminalEvent() {
        given(conversations.exists(1L)).willReturn(true);
        given(capability.model()).willReturn(Optional.of("chat-model"));
        willAnswer(call -> {
                    Consumer<ChatEvent> sink = call.getArgument(2);
                    Runnable finished = call.getArgument(3);
                    Thread.ofVirtual().start(() -> {
                        try {
                            Thread.sleep(QUEUED);
                            sink.accept(new ChatTurnStarted(7L));
                            sink.accept(new ChatDone(ChatTurnState.DONE));
                        } catch (InterruptedException | RuntimeException e) {
                            // The stream is already closed: the assertion below says so.
                        } finally {
                            finished.run();
                        }
                    });
                    return null;
                })
                .given(turns)
                .start(eq(1L), anyString(), any(), any());

        List<TurnStream.Event> events = TurnStream.ask(port, 1L, "Queued?");

        assertThat(TurnStream.names(events)).containsExactly("turn", "done");
    }
}
