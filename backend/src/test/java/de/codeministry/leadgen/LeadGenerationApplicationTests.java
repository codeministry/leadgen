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
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

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

        assertThat(tables).contains("source", "offer", "ingest_cursor", "flyway_schema_history");
    }

    @Test
    void theConfigurationLayerIsUpBeforeAnythingElseNeedsIt() {
        assertThat(config.snapshot().sources().sources()).isNotEmpty();
    }

    /** Empty: nothing overrides, so every file comes from the classpath. */
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
