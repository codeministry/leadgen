/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.codeministry.leadgen.Databases;
import jakarta.validation.Validator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The fixture set closes every placeholder the shipped defaults name, and a test context
 * resolves through it and through nothing else.
 *
 * <p>The failure this guards against was measured: with {@code AUTH_MODE=oidc} exported in
 * the shell, fifteen MockMvc probes refused to start their context with
 * {@code security.auth is 'oidc' and security.oidc.issuer is empty}, because the
 * {@code ConfigLoader} bean resolved {@code ${AUTH_MODE:none}} from the process environment
 * and the developer's {@code .env}. The earlier fix emptied {@code ${LLM_*}} in one test's
 * materialised copy, which closed one family of placeholders in one test and left every
 * other one open.
 */
class ConfigFixturesTest {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)(?::[^}]*)?}");

    private static final List<String> SHIPPED = List.of(
            ConfigLoader.PIPELINE_FILE,
            ConfigLoader.RULES_FILE,
            ConfigLoader.SOURCES_FILE,
            ConfigLoader.PROFILE_FILE,
            ConfigLoader.STYLE_FILE);

    @Test
    void everyPlaceholderInTheShippedDefaultsIsClosedByTheNeutralSet() {
        Map<String, String> found = shippedPlaceholders();

        assertThat(ConfigFixtures.NEUTRAL_PLACEHOLDERS.keySet())
                .as("a placeholder in the shipped defaults that ConfigFixtures.NEUTRAL_PLACEHOLDERS does not close"
                        + " — add it there, or a value from the developer's environment decides a test")
                .containsAll(found.keySet());
    }

    @Test
    void theNeutralSetNamesNothingTheShippedDefaultsDoNot() {
        Map<String, String> found = shippedPlaceholders();

        assertThat(found.keySet())
                .as("an entry in ConfigFixtures.NEUTRAL_PLACEHOLDERS that no shipped file names any more"
                        + " — remove it, so the set stays the list of what the defaults actually read")
                .containsAll(ConfigFixtures.NEUTRAL_PLACEHOLDERS.keySet());
    }

    @Test
    void theNeutralResolverRefusesAPlaceholderNobodyListed() {
        // Loud rather than lenient: a lenient resolver would hand a new placeholder its
        // default and nothing would say that the set had fallen behind the files.
        assertThatThrownBy(() -> ConfigFixtures.neutralResolver().resolve("auth: ${NOT_LISTED:none}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("NOT_LISTED")
                .hasMessageContaining("NEUTRAL_PLACEHOLDERS");
    }

    @Test
    void theNeutralResolverClosesAListedPlaceholderWithTheSetsOwnValue() {
        assertThat(ConfigFixtures.neutralResolver().resolve("auth: ${AUTH_MODE:none}\nhost: ${IMAP_HOST}"))
                .isEqualTo("auth: none\nhost: imap.invalid");
    }

    /**
     * Every placeholder name in the five shipped files, first file and line it appears on.
     */
    private static Map<String, String> shippedPlaceholders() {
        Map<String, String> found = new LinkedHashMap<>();
        for (String name : SHIPPED) {
            String content = ConfigSource.fromClasspath(name)
                    .orElseThrow(() -> new IllegalStateException("no default ships for " + name))
                    .content();
            Matcher matcher = PLACEHOLDER.matcher(content);
            while (matcher.find()) {
                found.putIfAbsent(matcher.group(1), name);
            }
        }
        return found;
    }

    /**
     * A context that says nothing about its configuration, which is the case every other
     * test is one forgotten {@code @DynamicPropertySource} away from.
     */
    @Nested
    @SpringBootTest
    @Testcontainers
    class AContextThatSaysNothing {

        @Container
        @ServiceConnection
        static final PostgreSQLContainer<?> POSTGRES = Databases.postgres();

        @Autowired
        private ConfigProperties properties;

        @Autowired
        private ConfigRegistry registry;

        @Autowired
        private Validator validator;

        @Test
        void readsTheShippedDefaultsAndNotTheOperatorsDirectory() {
            // `.env` puts LEADGEN_CONFIG_DIR into Spring's environment, so without this the
            // context reads the developer's own `config/` and passes or fails with it.
            assertThat(properties.configDirectory()).isEqualTo(ConfigFixtures.shippedDefaults());
        }

        @Test
        void resolvesEveryPlaceholderFromTheNeutralSetAndNotFromTheMachine() {
            ConfigSnapshot neutral = ConfigFixtures.loaderFor(
                            ConfigFixtures.shippedDefaults(), validator, ConfigFixtures.NEUTRAL_PLACEHOLDERS)
                    .load();
            ConfigSnapshot loaded = registry.snapshot();

            // Component by component and not the record: `loadedAt` differs by construction.
            assertThat(loaded.application()).isEqualTo(neutral.application());
            assertThat(loaded.rules()).isEqualTo(neutral.rules());
            assertThat(loaded.sources()).isEqualTo(neutral.sources());
            assertThat(loaded.profile()).isEqualTo(neutral.profile());
            assertThat(loaded.coverLetter()).isEqualTo(neutral.coverLetter());
        }
    }
}
