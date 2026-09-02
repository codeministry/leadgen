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

import org.junit.jupiter.api.Test;

class SecretsTest {

    @Test
    void masksWhatTheKeyNameGivesAway() {
        assertThat(Secrets.isSecret("POSTGRES_PASSWORD")).isTrue();
        assertThat(Secrets.isSecret("LLM_API_KEY")).isTrue();
        assertThat(Secrets.isSecret("spring.datasource.password")).isTrue();
        assertThat(Secrets.isSecret("IMAP_PASSWORD")).isTrue();
        assertThat(Secrets.isSecret("client-secret")).isTrue();
        assertThat(Secrets.isSecret("LLM_APIKEY")).isTrue();
    }

    @Test
    void leavesEverythingElseReadable() {
        assertThat(Secrets.isSecret("POSTGRES_HOST")).isFalse();
        assertThat(Secrets.isSecret("AUTH_MODE")).isFalse();
        assertThat(Secrets.isSecret("LEADGEN_CONFIG_DIR")).isFalse();
        assertThat(Secrets.isSecret("leadgen.config-dir")).isFalse();
        assertThat(Secrets.isSecret("NEWSLETTER_BLOCK_SELECTOR")).isFalse();
    }

    @Test
    void separatesMaskedFromUnsetFromEmpty() {
        assertThat(Secrets.mask("LLM_API_KEY", "sk-very-secret")).isEqualTo(Secrets.MASK);
        assertThat(Secrets.mask("LLM_API_KEY", null)).isEqualTo(Secrets.UNSET);
        assertThat(Secrets.mask("LLM_API_KEY", "")).isEqualTo(Secrets.EMPTY);
    }

    @Test
    void theMaskSaysNothingAboutTheLength() {
        assertThat(Secrets.mask("PASSWORD", "a")).isEqualTo(Secrets.mask("PASSWORD", "a".repeat(40)));
    }

    @Test
    void masksCredentialsInsideAValueTooWhateverTheKeyIs() {
        assertThat(Secrets.mask("spring.datasource.url", "jdbc:postgresql://user:hunter2@db:5432/leadgen"))
                .isEqualTo("jdbc:postgresql://user:" + Secrets.MASK + "@db:5432/leadgen");
    }

    @Test
    void leavesAHostAndPortAlone() {
        String url = "jdbc:postgresql://localhost:55432/leadgen";
        assertThat(Secrets.mask("spring.datasource.url", url)).isEqualTo(url);
    }
}
