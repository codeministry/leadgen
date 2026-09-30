/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import de.codeministry.leadgen.Databases;
import de.codeministry.leadgen.config.ConfigFixtures;
import de.codeministry.leadgen.config.Secrets;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
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
 * No MCP answer carries the configured mailbox address or a value the startup banner masks, whichever
 * tool returned it (spec 023, ISC-485).
 *
 * <p>The three values are planted where a tool reads them back: an offer's title, description and
 * advert, an application's note and one of its events' notes, and a role in the skill profile. All
 * ten tools are called and every response is read as the client receives it, the whole JSON-RPC
 * result. The premise is checked too: the planted text arrives masked, so it was read and changed,
 * not merely never returned.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class McpRedactionTest {

    private static final String ADDRESS = "mailbox.owner@example.invalid";

    private static final String PASSWORD = "Kx9-vault-Passphrase";

    private static final String ENVIRONMENT_SECRET = "Zq7-env-Secret-Value";

    private static final List<String> LEAKS = List.of(ADDRESS, PASSWORD, ENVIRONMENT_SECRET);

    private static final String PLANTED = "Write to " + ADDRESS + " with " + PASSWORD + " or " + ENVIRONMENT_SECRET;

    private static final String MASKED = "Write to " + Secrets.MASK + " with " + Secrets.MASK + " or " + Secrets.MASK;

    private static final Path CONFIG = seeded();

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

    @Test
    void noAnswerCarriesTheAddressOrASecret() {
        long offer = plant();
        var client = McpTestClient.connect("http://localhost:" + port);
        var calls = new LinkedHashMap<String, String>();
        calls.put("leadgen_search_offers", "{}");
        calls.put("leadgen_get_offer", "{\"id\":%d,\"includeFullText\":true}".formatted(offer));
        calls.put("leadgen_funnel_stats", "{}");
        calls.put("leadgen_ingest_status", "{}");
        calls.put("leadgen_list_applications", "{}");
        calls.put("leadgen_get_pipeline_config", "{\"section\":\"sources\"}");
        calls.put("leadgen_semantic_search", "{\"query\":\"" + PLANTED + "\"}");
        calls.put("leadgen_statistics", "{}");
        calls.put("leadgen_application", "{\"offerId\":%d}".formatted(offer));
        calls.put("leadgen_profile", "{}");
        assertThat(calls.keySet()).containsExactlyInAnyOrderElementsOf(client.toolNames());

        var answers = new LinkedHashMap<String, String>();
        calls.forEach((tool, arguments) ->
                answers.put(tool, client.callTool(tool, arguments).toString()));

        answers.forEach((tool, answer) -> {
            for (String leak : LEAKS) {
                assertThat(answer).as("%s's answer", tool).doesNotContainIgnoringCase(leak);
            }
        });
        // The premise: the planted text came back, masked. Title, description and advert in the
        // offer; the title in the search and on the board; the note and the event's note in the
        // application, whose title is the offer's too.
        assertThat(count(answers.get("leadgen_get_offer"))).isGreaterThanOrEqualTo(3);
        assertThat(count(answers.get("leadgen_search_offers"))).isGreaterThanOrEqualTo(1);
        assertThat(count(answers.get("leadgen_list_applications"))).isGreaterThanOrEqualTo(1);
        assertThat(count(answers.get("leadgen_application"))).isGreaterThanOrEqualTo(2);
        assertThat(answers.get("leadgen_profile")).contains("Reach me at " + Secrets.MASK);
    }

    private static int count(String answer) {
        return StringUtils.countOccurrencesOf(answer, MASKED);
    }

    private long plant() {
        long source =
                jdbc.queryForObject("INSERT INTO source (name, kind) VALUES ('red', 'file') RETURNING id", Long.class);
        long offer = jdbc.queryForObject("""
                INSERT INTO offer (source_id, external_id, title, description, full_text, url, fingerprint, status,
                                   score_value, score_band, portal, ingested_at)
                VALUES (?, 'red-1', ?, ?, ?, 'https://example.invalid/red', 'red-1', 'PASSED', 80, 'shortlist',
                        'portal-a', now())
                RETURNING id
                """, Long.class, source, "Kafka Developer · " + PLANTED, PLANTED, PLANTED);
        long application = jdbc.queryForObject(
                "INSERT INTO application (offer_id, status, note) VALUES (?, 'SENT', ?) RETURNING id",
                Long.class,
                offer,
                PLANTED);
        jdbc.update(
                "INSERT INTO application_event (application_id, from_status, to_status, note)"
                        + " VALUES (?, 'PACKAGED', 'SENT', ?)",
                application,
                PLANTED);
        return offer;
    }

    /** The mailbox's login set to the address and password, and the profile carrying both. */
    private static Path seeded() {
        try {
            Path dir = Files.createTempDirectory("leadgen-mcp-redaction");
            dir.toFile().deleteOnExit();
            ConfigFixtures.materialize(dir);
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
