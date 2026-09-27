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
import de.codeministry.leadgen.config.Secrets;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.StringUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * ISC-434: no request body the chat model receives carries the configured mailbox address or a
 * value {@link Secrets} masks, whichever tool produced the text.
 *
 * <p>The three values are configured the way an operator would: the address and password as the
 * mailbox connection's login in {@code sources.yaml}, and a third secret as a Spring property
 * under a secret-sounding name. Then all three are planted where the tools read — an offer's title
 * and advert, a profile field, an application's note and an event note — and a turn calls every
 * tool. What is read is the model's side of the wire: every body the stub received, system prompt,
 * tool definitions and tool results together.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ChatRedactionTest {

    private static final String ADDRESS = "mailbox.owner@example.invalid";

    private static final String PASSWORD = "Kx9-vault-Passphrase";

    private static final String ENVIRONMENT_SECRET = "Zq7-env-Secret-Value";

    private static final List<String> LEAKS = List.of(ADDRESS, PASSWORD, ENVIRONMENT_SECRET);

    private static final ModelStub MODEL = ModelStub.start();

    /** Created once, never inside the property supplier — see {@code LlmBudgetTest}. */
    private static final Path CONFIG = seeded(MODEL.configuration());

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = Databases.postgres();

    @DynamicPropertySource
    static void configuration(DynamicPropertyRegistry registry) {
        registry.add("leadgen.config-dir", CONFIG::toString);
        registry.add("leadgen.test.client-secret", () -> ENVIRONMENT_SECRET);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @AfterAll
    static void stop() {
        MODEL.close();
    }

    @Test
    void noBodyTheModelReceivesCarriesTheAddressOrASecret() {
        String planted = "Write to " + ADDRESS + " with " + PASSWORD + " or " + ENVIRONMENT_SECRET;
        long source =
                jdbc.queryForObject("INSERT INTO source (name, kind) VALUES ('red', 'file') RETURNING id", Long.class);
        long offer = jdbc.queryForObject("""
                INSERT INTO offer (source_id, external_id, title, description, url, fingerprint, status,
                                   score_value, portal, ingested_at)
                VALUES (?, 'red-1', ?, ?, 'https://example.invalid/red', 'red-1', 'PASSED', 80, 'portal-a', now())
                RETURNING id
                """, Long.class, source, "Kafka Developer · " + planted, planted);
        long application = jdbc.queryForObject(
                "INSERT INTO application (offer_id, status, note) VALUES (?, 'SENT', ?) RETURNING id",
                Long.class,
                offer,
                planted);
        jdbc.update(
                "INSERT INTO application_event (application_id, from_status, to_status, note)"
                        + " VALUES (?, 'PACKAGED', 'SENT', ?)",
                application,
                planted);
        MODEL.enqueue(ModelStub.toolCalls(
                "search_offers", "{\"text\":\"kafka\"}",
                "search_by_meaning", "{\"query\":\"event streaming\"}",
                "statistics", "{}",
                "application", "{\"offerId\":" + offer + "}",
                "profile", "{}"));
        MODEL.enqueue(ModelStub.text(Duration.ofMillis(5), "Nothing ", "to see."));
        long conversation =
                jdbc.queryForObject("INSERT INTO chat_conversation (title) VALUES ('') RETURNING id", Long.class);

        List<TurnStream.Event> events = TurnStream.ask(port, conversation, "What is written about Kafka?");

        assertThat(TurnStream.names(events).getLast()).isEqualTo("done");
        List<String> bodies = MODEL.bodies();
        assertThat(bodies).hasSize(2);
        for (String body : bodies) {
            for (String leak : LEAKS) {
                assertThat(body).as("a body the model received").doesNotContainIgnoringCase(leak);
            }
        }

        // The premise: the tools' results reached the model masked, so the planted text was read
        // and changed, not merely never read.
        // Four times: the title in two tools' results, the application's note, the event's note.
        String masked = "Write to " + Secrets.MASK + " with " + Secrets.MASK + " or " + Secrets.MASK;
        assertThat(StringUtils.countOccurrencesOf(bodies.getLast(), masked)).isEqualTo(4);
        assertThat(bodies.getLast()).contains("Reach me at " + Secrets.MASK);
    }

    /** The mailbox's login set to the address and password, and the profile carrying both. */
    private static Path seeded(Path dir) {
        try {
            rewrite(
                    dir.resolve("sources.yaml"),
                    "(?m)^(\\s*)username: \\$\\{IMAP_USER}.*$",
                    "$1username: " + ADDRESS,
                    "(?m)^(\\s*)password: \\$\\{IMAP_PASSWORD}.*$",
                    "$1password: " + PASSWORD);
            rewrite(
                    dir.resolve("skill-profile.yaml"),
                    "(?m)^(\\s*)roles: .*$",
                    "$1roles: [ \"Reach me at " + ADDRESS + "\", \"" + PASSWORD + "\" ]");
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void rewrite(Path file, String... patternThenReplacement) throws IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        for (int i = 0; i < patternThenReplacement.length; i += 2) {
            String changed = text.replaceFirst(patternThenReplacement[i], patternThenReplacement[i + 1]);
            if (changed.equals(text)) {
                throw new IllegalStateException("no match for " + patternThenReplacement[i] + " in " + file);
            }
            text = changed;
        }
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }
}
