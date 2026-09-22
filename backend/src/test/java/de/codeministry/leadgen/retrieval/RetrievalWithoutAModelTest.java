/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import de.codeministry.leadgen.Databases;
import de.codeministry.leadgen.config.ConfigFixtures;
import de.codeministry.leadgen.ingest.extract.TitleNormalizer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * Rules before model, on this stage: the state of a fresh clone on its first morning.
 *
 * <p>The shipped configuration has {@code retrieval.enabled} off and no embedding key, and the
 * pipeline has to run through unchanged. What is lost is the semantic search, and nothing else
 * — which is exactly why the search is a filter that narrows rather than an order that ranks:
 * an absent filter leaves a working screen behind it.
 */
@SpringBootTest
@Testcontainers
class RetrievalWithoutAModelTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = Databases.postgres();

    /**
     * Built once in a static field: a {@code @DynamicPropertySource} supplier runs once per
     * resolution, so one that creates a directory hands out a different one each time.
     */
    private static final Path CONFIG = shippedDefaults();

    @Autowired
    private RetrievalIndexService index;

    @Autowired
    private JdbcTemplate jdbc;

    private long sourceId;

    @DynamicPropertySource
    static void configuration(DynamicPropertyRegistry registry) {
        registry.add("leadgen.config-dir", () -> CONFIG.toString());
    }

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM offer");
        jdbc.update("DELETE FROM source");
        sourceId =
                jdbc.queryForObject("INSERT INTO source (name, kind) VALUES ('test', 'file') RETURNING id", Long.class);
    }

    @Test
    void writesNothingAndSaysSoRatherThanFailing() {
        long offer = segmented("Senior Java Entwickler (m/w/d)", "Ablösung eines Monolithen.");

        RetrievalReport report = index.run();

        assertThat(report.embedded()).isZero();
        assertThat(report.requests()).isZero();
        assertThat(report.model()).isNull();
        assertThat(jdbc.queryForObject(
                        "SELECT retrieval_embedding IS NULL FROM offer WHERE id = ?", Boolean.class, offer))
                .isTrue();
    }

    @Test
    void leavesTheDedupeColumnAloneHereToo() {
        // The stage that does nothing must also do nothing to the other column, which is the
        // one assertion a "did not run" test usually forgets to make.
        long offer = segmented("Senior Java Entwickler (m/w/d)", "Ablösung eines Monolithen.");

        index.run();

        assertThat(jdbc.queryForObject("SELECT embedding IS NULL FROM offer WHERE id = ?", Boolean.class, offer))
                .isTrue();
    }

    private long segmented(String title, String advert) {
        long id = jdbc.queryForObject(
                """
                INSERT INTO offer (source_id, external_id, title, description, url, location, portal, fingerprint)
                VALUES (?, ?, ?, 'Teaser.', ?, 'Köln', 'portal-a', ?)
                RETURNING id
                """,
                Long.class,
                sourceId,
                "ext-" + title.hashCode(),
                title,
                "https://example.invalid/" + title.hashCode(),
                TitleNormalizer.normalize(title));
        jdbc.update(
                """
                UPDATE offer
                   SET content_blocks = CAST(? AS jsonb), full_text = ?, content_at = now(), status = 'PASSED'
                 WHERE id = ?
                """,
                "[{\"index\":0,\"kind\":\"CONTENT\",\"text\":\"%s\",\"reason\":\"t\",\"by\":\"RULE\"}]"
                        .formatted(advert),
                advert,
                id);
        return id;
    }

    /**
     * The shipped defaults, with the {@code ${LLM_*}} placeholders emptied.
     *
     * <p>Not decoration. The resolver reads the developer's own `.env` behind the process
     * environment, so a key on the machine running the build turns "no model configured" into a
     * real run against a real endpoint — and the test that exists to prove the keyless path
     * fails for the one person who has finished configuring it. {@code ScoringWithoutAModelTest}
     * learned this the hard way and this is the same guard.
     *
     * <p>{@code retrieval.enabled} is left exactly as it ships, because "off by default" is half
     * of what this test is about.
     */
    private static Path shippedDefaults() {
        try {
            Path dir = Files.createTempDirectory("leadgen-retrieval-nomodel");
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
