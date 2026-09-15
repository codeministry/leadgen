/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What may leave the machine when a configuration file is shown in a browser.
 *
 * <p>No container and no context: the rule is a pure function, which is the point of putting
 * it in one. Every case below is something a person's own `config/sources.yaml` can contain,
 * on an endpoint that stands behind nothing.
 */
class YamlMaskTest {

    @Test
    void masksALiteralUnderASecretKey() {
        assertThat(YamlMask.apply("    password: hunter2\n")).isEqualTo("    password: ********\n");
    }

    @Test
    void keepsABarePlaceholderBecauseAVariableNameIsNotASecret() {
        // Half the reason to open this panel is to find out which variable has to be set.
        assertThat(YamlMask.apply("    password: ${IMAP_PASSWORD}\n"))
            .isEqualTo("    password: ${IMAP_PASSWORD}\n");
    }

    @Test
    void masksTheDefaultWrittenInsideAPlaceholder() {
        assertThat(YamlMask.apply("    password: ${IMAP_PASSWORD:hunter2}\n"))
            .isEqualTo("    password: ${IMAP_PASSWORD:********}\n");
    }

    @Test
    void masksAMailboxAddressAlthoughTheBannerPrintsOne() {
        // `username` is not secret by `Secrets`' rule and must not become one: the banner's
        // job is "is it set" on the operator's own terminal. In a browser tab it is a personal
        // datum, so the view masks more than the banner does.
        assertThat(Secrets.isSecret("username")).isFalse();
        assertThat(YamlMask.apply("    username: marcello@example.com\n"))
            .isEqualTo("    username: ********\n");
    }

    @Test
    void neverWritesTextTheFileDoesNotContain() {
        // `Secrets` renders a null as "(not set)" and an empty value as "(empty)". Both are
        // renderings of a *resolved* value: in a file an absent key is an absent line, and a
        // key with nothing after it is meaningful exactly as written.
        String masked = YamlMask.apply("    password:\n    host: \n");

        assertThat(masked).doesNotContain(Secrets.UNSET).doesNotContain(Secrets.EMPTY);
        assertThat(masked).isEqualTo("    password:\n    host: \n");
    }

    @Test
    void reachesIntoAFlowMappingBecauseThisFileIsFullOfThem() {
        // A line-and-colon masker misses this entirely, and the shipped file's whole `fields:`
        // section is written this way.
        assertThat(YamlMask.apply("    defaults: { token: abc123, language: de }\n"))
            .isEqualTo("    defaults: { token: ********, language: de }\n");
    }

    @Test
    void masksAWholeBlockScalarUnderASecretKey() {
        String masked = YamlMask.apply("""
            connections:
              - id: one
                password: >
                  a secret spread
                  over two lines
                type: imap
            """);

        assertThat(masked).doesNotContain("a secret spread").doesNotContain("over two lines");
        assertThat(masked).contains("type: imap");
    }

    @Test
    void masksAPasswordInsideAUrlAndATokenInItsQuery() {
        // The same "the name decides" rule, one level down: a portal's saved search routinely
        // carries a token, and the key it sits under is `url`.
        assertThat(YamlMask.apply("    url: https://user:hunter2@portal.example/feed\n"))
            .isEqualTo("    url: https://user:********@portal.example/feed\n");
        assertThat(YamlMask.apply("    url: https://portal.example/s?token=abc&q=java\n"))
            .isEqualTo("    url: https://portal.example/s?token=********&q=java\n");
    }

    @Test
    void leavesAnOrdinaryValueAndItsTrailingCommentAlone() {
        assertThat(YamlMask.apply("    mode: poll              # poll | idle\n"))
            .isEqualTo("    mode: poll              # poll | idle\n");
    }

    @Test
    void changesNothingInTheShippedFile() {
        // The file that ships names every value as a ${PLACEHOLDER}, by the invariant that
        // nothing is wired in. So masking it is a no-op, and the day it stops being one is the
        // day a literal crept into a committed file.
        String shipped = ConfigSource.fromClasspath(ConfigLoader.SOURCES_FILE).orElseThrow().content();

        assertThat(YamlMask.apply(shipped).strip()).isEqualTo(shipped.strip());
    }
}
