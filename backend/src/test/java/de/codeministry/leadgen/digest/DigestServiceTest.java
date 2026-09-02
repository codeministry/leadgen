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
 * ISC-49: the daily digest is a file, it lists both bands, and it is produced without a
 * frontend.
 */
@SpringBootTest
@Testcontainers
class DigestServiceTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    private static Path outputDir;

    @Autowired
    private DigestService digest;

    @Autowired
    private JdbcTemplate jdbc;

    private long sourceId;

    @DynamicPropertySource
    static void configuration(DynamicPropertyRegistry registry) {
        registry.add("leadgen.config-dir", () -> configWritingTextTo().toString());
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
    void writesAFileWithBothBandsAndTheReasonsBehindEveryNumber() {
        long strong = offer("Senior Java Entwickler (m/w/d)", 88, "SHORTLISTED");
        reason(strong, "core_skill_overlap", "2 of 2 core skills named: Java, Spring Boot", 45);
        long middling = offer("Java Entwickler Schnittstellen (m/w/d)", 61, "REVIEW");
        reason(middling, "vague_description", "no detail on the systems being connected", -10);

        var file = digest.render(LocalDate.of(2026, 9, 1));

        assertThat(file).isPresent();
        String text = read(file.orElseThrow());
        assertThat(text)
                .contains("Shortlisted")
                .contains("For review")
                .contains("Senior Java Entwickler")
                .contains("Java Entwickler Schnittstellen")
                .contains("2 of 2 core skills named")
                .contains("no detail on the systems being connected")
                .contains("+45")
                .contains("-10");
    }

    @Test
    void showsAnUnscoredOfferUnderItsOwnHeading() {
        // Without a model there is no ranking to trust, and sorting an unscored offer to
        // the bottom of the shortlist would pretend there is one.
        long id = offer("Senior Java Entwickler (m/w/d)", null, "UNSCORED");
        reason(id, "core_skill_overlap", "2 of 2 core skills named: Java, Spring Boot", 45);

        String text = read(digest.render(LocalDate.of(2026, 9, 1)).orElseThrow());

        assertThat(text).contains("Unscored").contains("no language model was configured");
    }

    @Test
    void saysWhatItIsNot() {
        // The tool has no send path at all, and the digest is where someone would most
        // expect one. Saying so in the artefact is cheaper than explaining it later.
        offer("Senior Java Entwickler (m/w/d)", 88, "SHORTLISTED");

        assertThat(read(digest.render(LocalDate.of(2026, 9, 1)).orElseThrow())).contains("Nothing here has been sent");
    }

    @Test
    void namesTheFileAfterTheDay() {
        offer("Senior Java Entwickler (m/w/d)", 88, "SHORTLISTED");

        assertThat(digest.render(LocalDate.of(2026, 9, 1))
                        .orElseThrow()
                        .getFileName()
                        .toString())
                .isEqualTo("digest-2026-09-01.txt");
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private long offer(String title, Integer score, String band) {
        return jdbc.queryForObject(
                """
                INSERT INTO offer (source_id, external_id, title, description, url, fingerprint,
                                   status, score_value, score_band, location, portal, agency)
                VALUES (?, ?, ?, 'egal', 'https://example.invalid/x', 'fp', 'PASSED', ?, ?,
                        'Köln', 'portal-a', 'Acme Consulting GmbH')
                RETURNING id
                """,
                Long.class,
                sourceId,
                "ext-" + System.nanoTime(),
                title,
                score,
                band);
    }

    private void reason(long offerId, String factor, String label, int points) {
        jdbc.update(
                "INSERT INTO offer_score_reason (offer_id, factor, label, points, position) VALUES (?, ?, ?, ?, 0)",
                offerId,
                factor,
                label,
                points);
    }

    private static Path configWritingTextTo() {
        try {
            Path dir = Files.createTempDirectory("leadgen-digest");
            dir.toFile().deleteOnExit();
            outputDir = Files.createTempDirectory("leadgen-digest-out");
            outputDir.toFile().deleteOnExit();
            ConfigFixtures.materialize(dir);

            Path pipeline = dir.resolve("pipeline.yaml");
            Files.writeString(
                    pipeline,
                    Files.readString(pipeline, StandardCharsets.UTF_8)
                            .replace("format: ${DIGEST_FORMAT:html}", "format: text")
                            .replace("output_dir: ${DIGEST_DIR:./packages/digest}", "output_dir: " + outputDir),
                    StandardCharsets.UTF_8);
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
