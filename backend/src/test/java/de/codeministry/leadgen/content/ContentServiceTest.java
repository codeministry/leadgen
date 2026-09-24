/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.content;

import static org.assertj.core.api.Assertions.assertThat;

import de.codeministry.leadgen.Databases;
import de.codeministry.leadgen.config.ConfigFixtures;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
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
 * {@link ContentService#runFor(long)} against a real database.
 *
 * <p>Its own class rather than a corner of {@code ContentSegmentationTest}, which is a plain
 * unit test of splitting and rules and should stay one: the one thing worth proving here
 * against a fixture is that the button's predicate touches its own offer and nothing beside
 * it, and that needs the schema.
 */
@SpringBootTest
@Testcontainers
class ContentServiceTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = Databases.postgres();

    /** Created once: a {@code @DynamicPropertySource} supplier may run more than once. */
    private static final Path CONFIG = keylessDefaults();

    @Autowired
    private ContentService content;

    @Autowired
    private JdbcTemplate jdbc;

    private long sourceId;

    @DynamicPropertySource
    static void configuration(DynamicPropertyRegistry registry) {
        registry.add("leadgen.config-dir", CONFIG::toString);
    }

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM offer");
        jdbc.update("DELETE FROM source");
        sourceId =
                jdbc.queryForObject("INSERT INTO source (name, kind) VALUES ('test', 'file') RETURNING id", Long.class);
    }

    @Test
    void segmentsExactlyTheOfferItWasAskedForAndLeavesAnotherDueOfferUntouched() {
        long target = dueOffer("/projekt/target");
        long other = dueOffer("/projekt/other");

        var report = content.runFor(target);

        assertThat(report.considered()).isEqualTo(1);
        assertThat(report.segmented()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT content_at FROM offer WHERE id = ?", Timestamp.class, target))
                .isNotNull();
        assertThat(jdbc.queryForObject("SELECT content_blocks FROM offer WHERE id = ?", String.class, target))
                .isNotNull();

        // The second offer is still due: `runFor` reads the same predicate as `run()`, with
        // `id = ?` added rather than substituted for it, so a call for one offer cannot reach
        // another the night would also have picked up.
        assertThat(jdbc.queryForObject("SELECT content_at FROM offer WHERE id = ?", Timestamp.class, other))
                .isNull();
        assertThat(jdbc.queryForObject("SELECT content_blocks FROM offer WHERE id = ?", String.class, other))
                .isNull();
    }

    @Test
    void answersSkippedForAnOfferTheNightWouldNotTouch() {
        // Archived, so the predicate excludes it even though it otherwise looks due. `runFor`
        // must not force a write through a path this offer does not belong on.
        long archived = dueOffer("/projekt/archived");
        jdbc.update("UPDATE offer SET archived_at = now() WHERE id = ?", archived);

        var report = content.runFor(archived);

        assertThat(report.considered()).isZero();
        assertThat(report.segmented()).isZero();
        assertThat(jdbc.queryForObject("SELECT content_at FROM offer WHERE id = ?", Timestamp.class, archived))
                .isNull();
    }

    private long dueOffer(String path) {
        return jdbc.queryForObject(
                """
                INSERT INTO offer (source_id, external_id, title, url, portal, fingerprint, status, full_text)
                VALUES (?, ?, 'Angular Entwickler (m/w/d)', ?, 'example.invalid', 'angular entwickler', 'PASSED', ?)
                RETURNING id
                """, Long.class, sourceId, path, "https://example.invalid" + path, ContentSegmentationTest.ADVERT);
    }

    /**
     * The shipped defaults with every {@code ${LLM_*}} placeholder emptied, the guard
     * {@code ScoringWithoutAModelTest} carries: the resolver reads the developer's own
     * {@code .env} behind the process environment, and a key configured there would turn this
     * test's "no classifier" assumption into a real call against a real endpoint.
     */
    private static Path keylessDefaults() {
        try {
            Path dir = Files.createTempDirectory("leadgen-content-runfor");
            dir.toFile().deleteOnExit();
            ConfigFixtures.materialize(dir);
            Path pipeline = dir.resolve("pipeline.yaml");
            Files.writeString(pipeline, Files.readString(pipeline).replaceAll("\\$\\{LLM_[A-Z_]+(?::[^}]*)?}", "''"));
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
