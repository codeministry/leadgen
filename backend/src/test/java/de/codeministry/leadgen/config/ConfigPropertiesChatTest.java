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

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * Fix 4B-8: {@code leadgen.chat.*} binds on {@link ConfigProperties} like every other {@code leadgen.*}
 * key, and its defaults live in one place — what Spring binds from nothing equals what the
 * hand-built constructor makes, so a second copy of {@code PT15M} has nowhere left to drift.
 */
class ConfigPropertiesChatTest {

    @Test
    void theBoundDefaultsAreTheHandBuiltOnes() {
        ConfigProperties bound = bind(Map.of("leadgen.config-dir", "config"));

        assertThat(bound).isEqualTo(new ConfigProperties("config"));
        assertThat(bound.chat().turnTimeout()).isPositive();
        assertThat(bound.chat().heartbeat()).isLessThan(bound.chat().turnTimeout());
    }

    @Test
    void aSetKeyOverridesItsDefault() {
        ConfigProperties bound = bind(Map.of(
                "leadgen.config-dir", "config",
                "leadgen.chat.turn-timeout", "PT2S",
                "leadgen.chat.heartbeat", "PT0.15S"));

        assertThat(bound.chat().turnTimeout()).isEqualTo(Duration.ofSeconds(2));
        assertThat(bound.chat().heartbeat()).isEqualTo(Duration.ofMillis(150));
    }

    private static ConfigProperties bind(Map<String, String> properties) {
        return new Binder(new MapConfigurationPropertySource(properties))
                .bind("leadgen", ConfigProperties.class)
                .get();
    }
}
