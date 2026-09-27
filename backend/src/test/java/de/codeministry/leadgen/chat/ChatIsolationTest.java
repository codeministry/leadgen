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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * ISC-422, the server half: no pipeline code reads anything the chat writes, so a run is the same
 * run with or without a chat model.
 *
 * <p>A source scan rather than a runtime check, because the failure it guards against is a line of
 * code that has not been written yet: a stage that one day joins {@code chat_turn} or injects the
 * conversation repository. Only {@code chat} itself and the controller that serves it may name them.
 */
class ChatIsolationTest {

    private static final Path MAIN = Path.of("src/main/java/de/codeministry/leadgen");

    private static final Pattern CHAT_STATE =
            Pattern.compile("chat_conversation|chat_turn|chat_tool_call|chat_call_budget|ConversationRepository");

    @Test
    void noPipelineCodeReadsWhatTheChatWrites() throws IOException {
        List<String> offenders;
        try (Stream<Path> files = Files.walk(MAIN)) {
            offenders = files.filter(file -> file.toString().endsWith(".java"))
                    .filter(file -> !file.startsWith(MAIN.resolve("chat")))
                    .filter(file -> !file.equals(MAIN.resolve("web/ChatController.java")))
                    // The native-image registry names every reflected type by design; it runs no stage.
                    .filter(file -> !file.equals(MAIN.resolve("LeadGenRuntimeHints.java")))
                    .filter(file -> CHAT_STATE.matcher(read(file)).find())
                    .map(Path::toString)
                    .toList();
        }
        assertThat(offenders)
                .as("pipeline code naming the chat's tables or repository")
                .isEmpty();
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
