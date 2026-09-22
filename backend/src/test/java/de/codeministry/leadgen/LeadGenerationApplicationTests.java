/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen;

import static org.assertj.core.api.Assertions.assertThat;

import de.codeministry.leadgen.config.ConfigRegistry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * The two things the skeleton owes: the context starts, and Flyway actually ran.
 * Asserting on a table rather than on an empty context is the difference that matters —
 * a missing `spring-boot-flyway` module leaves the migrations on the classpath,
 * unexecuted, and an empty context test stays green through it.
 *
 * <p>The configuration directory is empty on purpose: the defaults on the classpath are
 * what a context has to be able to boot on. Pointing it at the user's own `config/local`
 * would make the result depend on whose machine it runs on.
 */
@SpringBootTest
@Testcontainers
class LeadGenerationApplicationTests {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = Databases.postgres();

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ConfigRegistry config;

    @DynamicPropertySource
    static void configDirectory(DynamicPropertyRegistry registry) {
        registry.add("leadgen.config-dir", () -> exampleConfigDirectory().toString());
    }

    @Test
    void flywayCreatedTheBaselineSchema() {
        var tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'", String.class);

        assertThat(tables).contains("source", "offer", "source_run", "flyway_schema_history");
        // Dropped in V21 with the classes that stopped reading it: progress is a user flag in
        // the mailbox now, and a schema that still offered the old mechanism was a promise the
        // code no longer kept.
        assertThat(tables).doesNotContain("ingest_cursor");
    }

    @Test
    void theVectorExtensionIsThereAndTheColumnHasAWidth() {
        // The image is the thing under test here. `pgvector/pgvector:0.8.6-pg18` is a plain
        // postgres with the extension added, and on any image without it `V22` fails at startup
        // naming the extension rather than the image — which is a long way from the compose file
        // that actually decides it.
        var extensions = jdbc.queryForList("SELECT extname FROM pg_extension", String.class);
        assertThat(extensions).contains("vector");

        // 2000 is stated in the column because an index cannot be built without it, so the
        // width is part of the schema rather than a detail of whichever model answered. It is
        // also the ceiling: pgvector refuses an HNSW index on a wider `vector`, which is why a
        // model returning more is truncated at the seam instead of widening this.
        Integer width = jdbc.queryForObject(
                "SELECT atttypmod FROM pg_attribute" + " WHERE attrelid = 'offer'::regclass AND attname = 'embedding'",
                Integer.class);
        assertThat(width).isEqualTo(2000);
    }

    @Test
    void theConfigurationLayerIsUpBeforeAnythingElseNeedsIt() {
        assertThat(config.snapshot().sources().sources()).isNotEmpty();
    }

    /**
     * Empty: nothing overrides, so every file comes from the classpath.
     */
    private static Path exampleConfigDirectory() {
        try {
            Path target = Files.createTempDirectory("leadgen-config");
            target.toFile().deleteOnExit();
            return target;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
