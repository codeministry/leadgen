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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The bun version is pinned in four files that nothing else ties together: {@code
 * packageManager} in {@code frontend/package.json}, {@code bun-version} in the two workflows,
 * and the {@code COPY --from=oven/bun:...} line of {@code frontend/Dockerfile}. They have to
 * name one version, or the image builds with a bun the lockfile was not written by. Renovate
 * groups the tag and the {@code packageManager} bump into one pull request for the same
 * reason; this is the check that the group did its job.
 *
 * <p>The files are declared as inputs of the {@code test} task in {@code backend/build.gradle.kts}
 * beside the other repository-level files, so an edit re-runs this.
 */
class BunPinTest {

    private static final Path ROOT = ConfigFixtures.repositoryRoot();

    private static final Map<String, Pattern> PINS = Map.of(
            "frontend/package.json", Pattern.compile("\"packageManager\":\\s*\"bun@([0-9.]+)\""),
            ".github/workflows/ci.yml", Pattern.compile("bun-version:\\s*'([0-9.]+)'"),
            ".github/workflows/release.yml", Pattern.compile("bun-version:\\s*'([0-9.]+)'"),
            "frontend/Dockerfile", Pattern.compile("COPY --from=oven/bun:([0-9.]+)-"));

    @Test
    void theFourPinsNameOneBunVersion() {
        var found = new LinkedHashMap<String, String>();
        PINS.forEach((file, pattern) -> {
            Matcher matcher = pattern.matcher(read(ROOT.resolve(file)));
            assertThat(matcher.find()).as("a bun pin in %s", file).isTrue();
            found.put(file, matcher.group(1));
        });
        assertThat(found.values().stream().distinct().count())
                .as("one bun version across %s", found)
                .isEqualTo(1);
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
