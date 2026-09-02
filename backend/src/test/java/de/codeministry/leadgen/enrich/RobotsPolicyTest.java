/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.enrich;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import org.junit.jupiter.api.Test;

/** The small part of robots.txt this crawler needs, and the conventions around it. */
class RobotsPolicyTest {

    private static RobotsPolicy serving(String body) {
        return new RobotsPolicy(uri -> body);
    }

    @Test
    void obeysADisallowedPrefix() {
        var policy = serving("User-agent: *\nDisallow: /intern/\n");

        assertThat(policy.allows(URI.create("https://portal.example/intern/ad"), "lead-generation/0.1"))
                .isFalse();
        assertThat(policy.allows(URI.create("https://portal.example/projekt/1"), "lead-generation/0.1"))
                .isTrue();
    }

    @Test
    void letsTheLongestMatchWin() {
        var policy = serving("User-agent: *\nDisallow: /a/\nAllow: /a/public/\n");

        assertThat(policy.allows(URI.create("https://portal.example/a/private"), "x"))
                .isFalse();
        assertThat(policy.allows(URI.create("https://portal.example/a/public/ad"), "x"))
                .isTrue();
    }

    @Test
    void prefersTheGroupThatNamesUsOverTheWildcard() {
        var policy = serving(
                """
                User-agent: *
                Disallow: /

                User-agent: lead-generation
                Disallow: /intern/
                """);

        assertThat(policy.allows(URI.create("https://portal.example/projekt/1"), "lead-generation/0.1"))
                .isTrue();
        assertThat(policy.allows(URI.create("https://portal.example/intern/x"), "lead-generation/0.1"))
                .isFalse();
    }

    @Test
    void readsAnEmptyDisallowAsNoRuleAtAll() {
        // "Disallow:" with nothing after it is the documented way to allow everything.
        var policy = serving("User-agent: *\nDisallow:\n");

        assertThat(policy.allows(URI.create("https://portal.example/anything"), "x"))
                .isTrue();
    }

    @Test
    void treatsAnUnreachableRobotsTxtAsOpen() {
        // The convention, and the alternative is worse: a host whose robots.txt times
        // out would silently stop being enriched, and the offers would look merely
        // incomplete with nothing pointing at the cause.
        var policy = new RobotsPolicy(uri -> {
            throw new IllegalStateException("timeout");
        });

        assertThat(policy.allows(URI.create("https://portal.example/projekt/1"), "x"))
                .isTrue();
    }

    @Test
    void ignoresComments() {
        var policy = serving("User-agent: *  # everyone\nDisallow: /intern/  # not this\n");

        assertThat(policy.allows(URI.create("https://portal.example/intern/x"), "x"))
                .isFalse();
    }
}
