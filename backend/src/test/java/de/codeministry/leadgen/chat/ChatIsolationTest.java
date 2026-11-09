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
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * ISC-422, the server half: no pipeline code reads anything the chat writes, so a run is the same
 * run with or without a chat model.
 *
 * <p>A source scan rather than a runtime check, because the failure it guards against is a line of
 * code that has not been written yet: a stage that one day joins {@code chat_turn} or injects the
 * conversation repository. Only {@code chat} itself and the controller that serves it may name them.
 *
 * <p>Two scans (ISC-541). The tables are read from the migrations rather than listed here, so the
 * next {@code chat_*} table is guarded the day it is created; and no pipeline code imports the
 * chat's package at all, so a writer that wants to tell the chat something publishes a neutral
 * fact from {@code event} instead.
 */
class ChatIsolationTest {

    private static final Path MAIN = Path.of("src/main/java/de/codeministry/leadgen");

    private static final Path MIGRATIONS = Path.of("src/main/resources/db/migration");

    private static final Pattern CREATED_TABLE =
            Pattern.compile("(?i)create\\s+table\\s+(?:if\\s+not\\s+exists\\s+)?(chat_\\w+)");

    /** Any reference to the chat's package — an import, a static import, a qualified name — outside a comment. */
    private static final Pattern CHAT_REFERENCE = Pattern.compile("\\bde\\.codeministry\\.leadgen\\.chat\\.");

    private static final Pattern COMMENT = Pattern.compile("(?s)/\\*.*?\\*/|//[^\\n]*");

    /** Beside {@code chat} itself, the code that serves the chat or wires it, and runs no stage. */
    private static final Set<Path> MAY_NAME_THE_CHAT = Set.of(
            MAIN.resolve("web/ChatController.java"),
            // The native-image registry names every reflected type by design.
            MAIN.resolve("LeadGenRuntimeHints.java"));

    /** May import the chat's types, though never read its tables: they expose the chat, not a stage. */
    private static final List<Path> MAY_IMPORT_THE_CHAT = List.of(
            MAIN.resolve("mcp"),
            // Validates the chat's prices block of pipeline.yaml at load.
            MAIN.resolve("config/ConfigLoader.java"));

    @Test
    void noPipelineCodeReadsWhatTheChatWrites() throws IOException {
        Pattern chatState = chatState();
        List<String> offenders = pipelineSources()
                .filter(file -> chatState.matcher(read(file)).find())
                .map(Path::toString)
                .toList();
        assertThat(offenders)
                .as("pipeline code naming the chat's tables (" + chatState.pattern() + ") or repository")
                .isEmpty();
    }

    @Test
    void noPipelineCodeImportsTheChat() throws IOException {
        List<String> offenders = pipelineSources()
                .filter(file -> MAY_IMPORT_THE_CHAT.stream().noneMatch(file::startsWith))
                .filter(file -> CHAT_REFERENCE
                        .matcher(COMMENT.matcher(read(file)).replaceAll(""))
                        .find())
                .map(Path::toString)
                .toList();
        assertThat(offenders)
                .as("pipeline code referring to de.codeministry.leadgen.chat")
                .isEmpty();
    }

    @Test
    void everyChatTableIsGuarded() throws IOException {
        // Guards the guard: eight chat tables exist today, and an empty scan would pass anything.
        assertThat(chatTables()).hasSizeGreaterThanOrEqualTo(8).contains("chat_suggestion_phrasing", "chat_context");
    }

    /** Every table a migration creates under the chat's prefix, and the repository the chat writes through. */
    private static Pattern chatState() throws IOException {
        return Pattern.compile(String.join("|", chatTables()) + "|ConversationRepository");
    }

    private static Set<String> chatTables() throws IOException {
        Set<String> tables = new TreeSet<>();
        try (Stream<Path> files = Files.list(MIGRATIONS)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".sql")).toList()) {
                Matcher matcher = CREATED_TABLE.matcher(read(file));
                while (matcher.find()) {
                    tables.add(matcher.group(1).toLowerCase(java.util.Locale.ROOT));
                }
            }
        }
        return tables;
    }

    private static Stream<Path> pipelineSources() throws IOException {
        try (Stream<Path> files = Files.walk(MAIN)) {
            return files
                    .filter(file -> file.toString().endsWith(".java"))
                    .filter(file -> !file.startsWith(MAIN.resolve("chat")))
                    .filter(file -> !MAY_NAME_THE_CHAT.contains(file))
                    .collect(Collectors.toList())
                    .stream();
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
