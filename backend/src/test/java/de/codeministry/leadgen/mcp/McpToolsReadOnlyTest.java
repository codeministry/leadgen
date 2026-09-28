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
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
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
import tools.jackson.databind.JsonNode;

/**
 * No MCP tool writes (spec 023, ISC-486): each of the ten says so in its annotations, and calling
 * every one of them leaves every table and the packages directory as they were.
 *
 * <p>Every table is compared by its row count and a digest of its rows, the chat's own tables
 * included, since an MCP call has no turn to record. The configuration is the shipped one, so the
 * search by meaning answers "unavailable" without asking a model; with retrieval on, its one write
 * is the day's counter in {@code llm_call_budget}, which {@code McpChatToolsTest} pins.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class McpToolsReadOnlyTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = Databases.postgres();

    private static final Path PACKAGES = temporary("leadgen-mcp-packages");

    private static final Path CONFIG = configuration(PACKAGES);

    @DynamicPropertySource
    static void configuration(DynamicPropertyRegistry registry) {
        registry.add("leadgen.config-dir", CONFIG::toString);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void everyToolIsAnnotatedReadOnly() {
        JsonNode tools = McpTestClient.connect("http://localhost:" + port).tools();
        assertThat(tools).hasSize(10);
        for (JsonNode tool : tools) {
            JsonNode annotations = tool.path("annotations");
            assertThat(annotations.path("readOnlyHint").asBoolean(false))
                    .as("%s is read-only", tool.path("name").asString())
                    .isTrue();
            assertThat(annotations.path("destructiveHint").asBoolean(true))
                    .as("%s is not destructive", tool.path("name").asString())
                    .isFalse();
        }
    }

    @Test
    void callingEveryToolChangesNoTableAndNoPackage() throws IOException {
        long offer = seed();
        var client = McpTestClient.connect("http://localhost:" + port);
        var calls = new LinkedHashMap<String, String>();
        calls.put("leadgen_search_offers", "{}");
        calls.put("leadgen_get_offer", "{\"id\":%d,\"includeFullText\":true}".formatted(offer));
        calls.put("leadgen_funnel_stats", "{}");
        calls.put("leadgen_ingest_status", "{}");
        calls.put("leadgen_list_applications", "{}");
        calls.put("leadgen_get_pipeline_config", "{\"section\":\"rules\"}");
        calls.put("leadgen_semantic_search", "{\"query\":\"event streaming\"}");
        calls.put("leadgen_statistics", "{}");
        calls.put("leadgen_application", "{\"offerId\":%d}".formatted(offer));
        calls.put("leadgen_profile", "{}");
        assertThat(calls.keySet()).containsExactlyInAnyOrderElementsOf(client.toolNames());
        Map<String, String> tablesBefore = tables();
        List<String> packagesBefore = packages();

        calls.forEach((tool, arguments) -> assertThat(
                        client.callTool(tool, arguments).path("isError").asBoolean(false))
                .as("%s answers", tool)
                .isFalse());

        // Guards the guard: a snapshot of nothing would compare equal to anything.
        assertThat(tablesBefore).containsKeys("offer", "application", "application_event", "chat_conversation");
        assertThat(packagesBefore).isNotEmpty();
        assertThat(tables()).isEqualTo(tablesBefore);
        assertThat(packages()).isEqualTo(packagesBefore);
    }

    private long seed() throws IOException {
        long source =
                jdbc.queryForObject("INSERT INTO source (name, kind) VALUES ('ro', 'file') RETURNING id", Long.class);
        Path folder = Files.createDirectories(PACKAGES.resolve("ro-1"));
        Files.writeString(folder.resolve("anschreiben.md"), "Guten Tag.", StandardCharsets.UTF_8);
        long offer = jdbc.queryForObject("""
                INSERT INTO offer (source_id, external_id, title, description, full_text, url, fingerprint, status,
                                   score_value, score_band, portal, ingested_at, package_dir, packaged_at)
                VALUES (?, 'ro-1', 'Kafka Developer', 'Event streaming.', 'The advert.', 'https://example.invalid/ro',
                        'ro-1', 'PASSED', 80, 'shortlist', 'portal-a', now(), 'ro-1', now())
                RETURNING id
                """, Long.class, source);
        long application = jdbc.queryForObject(
                "INSERT INTO application (offer_id, status, note) VALUES (?, 'PACKAGED', 'read twice') RETURNING id",
                Long.class,
                offer);
        jdbc.update(
                "INSERT INTO application_event (application_id, from_status, to_status) VALUES (?, 'SHORTLISTED', 'PACKAGED')",
                application);
        return offer;
    }

    /** Every table, as its row count and a digest of its rows. */
    private Map<String, String> tables() {
        Map<String, String> tables = new LinkedHashMap<>();
        for (String table : jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables
                 WHERE table_schema = current_schema() AND table_type = 'BASE TABLE'
                   AND table_name <> 'flyway_schema_history'
                 ORDER BY table_name
                """, String.class)) {
            tables.put(
                    table,
                    jdbc.queryForObject(
                            "SELECT count(*) || ':' || coalesce(md5(string_agg(t::text, '|' ORDER BY t::text)), '')"
                                    + " FROM \"" + table + "\" t",
                            String.class));
        }
        return tables;
    }

    /** Every path under the packages directory with its size and modification time. */
    private static List<String> packages() throws IOException {
        try (Stream<Path> paths = Files.walk(PACKAGES)) {
            return paths.sorted()
                    .map(path -> PACKAGES.relativize(path) + " " + size(path) + " " + modified(path))
                    .toList();
        }
    }

    private static long size(Path path) {
        try {
            return Files.isDirectory(path) ? -1 : Files.size(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String modified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toString();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path temporary(String prefix) {
        try {
            Path dir = Files.createTempDirectory(prefix);
            dir.toFile().deleteOnExit();
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The shipped files with the packages written to a directory of this test's own. */
    private static Path configuration(Path packages) {
        try {
            Path dir = temporary("leadgen-mcp-read-only");
            ConfigFixtures.materialize(dir);
            Path pipeline = dir.resolve("pipeline.yaml");
            String shipped = Files.readString(pipeline, StandardCharsets.UTF_8);
            String text = shipped.replace("output_dir: ${PACKAGES_DIR:./packages}", "output_dir: " + packages);
            if (text.equals(shipped)) {
                throw new IllegalStateException("the shipped pipeline.yaml no longer names PACKAGES_DIR");
            }
            Files.writeString(pipeline, text, StandardCharsets.UTF_8);
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
