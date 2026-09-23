/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.digest;

import static org.assertj.core.api.Assertions.assertThat;

import de.codeministry.leadgen.Databases;
import de.codeministry.leadgen.config.ConfigFixtures;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The HTML digest, which {@link DigestServiceTest} cannot see because that class runs the
 * text format.
 *
 * <p>Its own class rather than a nested one, because the format is read out of the
 * configuration directory and that is bound once per application context.
 */
@SpringBootTest
@Testcontainers
class DigestHtmlTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = Databases.postgres();

    private static Path outputDir;

    @Autowired
    private DigestService digest;

    @Autowired
    private JdbcTemplate jdbc;

    private long sourceId;

    @DynamicPropertySource
    static void configuration(DynamicPropertyRegistry registry) {
        registry.add("leadgen.config-dir", () -> configWritingHtmlTo().toString());
    }

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM offer_score_reason");
        jdbc.update("DELETE FROM offer");
        jdbc.update("DELETE FROM source");
        sourceId =
                jdbc.queryForObject("INSERT INTO source (name, kind) VALUES ('test', 'file') RETURNING id", Long.class);
    }

    @Test
    void opensTheOriginalAdInANewTabAndNeverHandsItTheOpener() {
        // Every link to the original ad leaves this tool, so it opens beside the digest rather
        // than replacing it. `noopener` is the half that is not cosmetic: the URL came off a
        // portal, and without it the page opened here can reach back through `window.opener`.
        offer("Senior Java Entwickler (m/w/d)", 88, "SHORTLISTED");

        String html = read(digest.render(LocalDate.of(2026, 9, 1)).orElseThrow());

        assertThat(html)
                .contains("<a target=\"_blank\" rel=\"noopener noreferrer\" href=\"https://example.invalid/x\">");
    }

    @Test
    void namesTheFileAfterTheDayAndTheFormat() {
        offer("Senior Java Entwickler (m/w/d)", 88, "SHORTLISTED");

        assertThat(digest.render(LocalDate.of(2026, 9, 1))
                        .orElseThrow()
                        .getFileName()
                        .toString())
                .isEqualTo("digest-2026-09-01.html");
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private long offer(String title, Integer score, String band) {
        return jdbc.queryForObject("""
            INSERT INTO offer (source_id, external_id, title, description, url, fingerprint,
                               status, score_value, score_band, location, portal, agency)
            VALUES (?, ?, ?, 'egal', 'https://example.invalid/x', 'fp', 'PASSED', ?, ?,
                    'Köln', 'portal-a', 'Acme Consulting GmbH')
            RETURNING id
            """, Long.class, sourceId, "ext-" + System.nanoTime(), title, score, band);
    }

    private static Path configWritingHtmlTo() {
        try {
            Path dir = Files.createTempDirectory("leadgen-digest-html");
            dir.toFile().deleteOnExit();
            outputDir = Files.createTempDirectory("leadgen-digest-html-out");
            outputDir.toFile().deleteOnExit();
            ConfigFixtures.materialize(dir);

            Path pipeline = dir.resolve("pipeline.yaml");
            Files.writeString(
                    pipeline,
                    Files.readString(pipeline, StandardCharsets.UTF_8)
                            // Pinned rather than left on the placeholder's default, so an exported
                            // DIGEST_FORMAT in a shell cannot turn this class into a second text run.
                            .replace("format: ${DIGEST_FORMAT:html}", "format: html")
                            .replace("output_dir: ${DIGEST_DIR:./packages/digest}", "output_dir: " + outputDir),
                    StandardCharsets.UTF_8);
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
