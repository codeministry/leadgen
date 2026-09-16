/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What happens when somebody writes a real credential into their own configuration file.
 *
 * <p>The shipped file names every value as a {@code ${PLACEHOLDER}}, so it can never prove
 * this. The second layer exists precisely so that a person can write their own file, and
 * nothing stops them writing a literal in it — this is that person, and the endpoint that
 * shows their file to a browser.
 */
@SpringBootTest
@Testcontainers
class SourceDetailMaskingTest {

    private static final String PASSWORD = "correct-horse-battery-staple";
    private static final String MAILBOX = "marcello@example.invalid";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    /**
     * Built once and remembered: a supplier is called on every resolution and not once per
     * context, so a fresh directory per call would give one context two configurations — the
     * trap {@code ConfigFixtures} already documents and {@code PackagingServiceTest} paid for.
     */
    private static Path directory;

    @DynamicPropertySource
    static void configuration(DynamicPropertyRegistry registry) {
        registry.add("leadgen.config-dir", () -> writtenByHand().toString());
    }

    private static synchronized Path writtenByHand() {
        if (directory != null) {
            return directory;
        }
        try {
            Path dir = Files.createTempDirectory("leadgen-literal-secrets");
            dir.toFile().deleteOnExit();
            ConfigFixtures.materialize(dir);
            Path sources = dir.resolve(ConfigLoader.SOURCES_FILE);
            String own = Files.readString(sources, StandardCharsets.UTF_8)
                .replace("${IMAP_PASSWORD}", PASSWORD)
                .replace("${IMAP_USER}", MAILBOX);
            Files.writeString(sources, own, StandardCharsets.UTF_8);
            directory = dir;
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Autowired
    private SourceDetailService details;

    /**
     * A mapper of its own: this context has no bean, and the assertion is about the fields.
     */
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void theCredentialNeverLeavesTheMachine() throws Exception {
        var detail = details.detail("sample-newsletter", 30).orElseThrow();

        // Serialised, because the assertion worth making is about the whole answer and not
        // about the field somebody remembered to check.
        String json = mapper.writeValueAsString(detail);

        assertThat(json).doesNotContain(PASSWORD).doesNotContain(MAILBOX);
        assertThat(detail.connection().text()).contains("password: " + Secrets.MASK);
        assertThat(detail.connection().text()).contains("username: " + Secrets.MASK);
    }

    @Test
    void keepsEverythingAboutTheConnectionThatIsNotTheCredential() {
        // Masking the block into uselessness would be the other way to fail: the settings
        // beside the password are exactly what somebody opens this panel to check.
        String block = details.detail("sample-newsletter", 30).orElseThrow().connection().text();

        assertThat(block).contains("type: imap").contains("mode: poll").contains("${IMAP_HOST}");
    }

    @Test
    void saysTheFileCameFromTheConfigurationDirectory() {
        var file = details.detail("sample-newsletter", 30).orElseThrow().file();

        assertThat(file.layer()).isEqualTo("config-dir");
        assertThat(file.name()).isEqualTo("sources.yaml");
    }
}
