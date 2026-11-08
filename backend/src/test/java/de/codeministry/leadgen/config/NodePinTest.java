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
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The Node the frontend runs on is named twice: the exact tag of the {@code node:...} build
 * stage in {@code frontend/Dockerfile}, and the major in the root {@code .nvmrc}, which CI and
 * the release gate read through {@code actions/setup-node}. Nothing else ties them together,
 * so an Angular bump that moves the image's tag would leave the gate testing on the old major
 * without a word. Same shape as {@link BunPinTest}.
 *
 * <p>The files are declared as inputs of the {@code test} task in {@code backend/build.gradle.kts},
 * so an edit re-runs this.
 */
class NodePinTest {

    private static final Path ROOT = ConfigFixtures.repositoryRoot();

    private static final Pattern IMAGE_MAJOR =
            Pattern.compile("FROM --platform=\\$BUILDPLATFORM node:([0-9]+)\\.[0-9.]+-");

    private static final Pattern NODE_VERSION_FILE = Pattern.compile("node-version-file:\\s*'\\.nvmrc'");

    @Test
    void nvmrcNamesTheMajorTheFrontendImageBuildsOn() {
        Matcher image = IMAGE_MAJOR.matcher(read(ROOT.resolve("frontend/Dockerfile")));
        assertThat(image.find()).as("a node build stage in frontend/Dockerfile").isTrue();

        assertThat(read(ROOT.resolve(".nvmrc")).strip())
                .as(".nvmrc against node:%s in frontend/Dockerfile", image.group(1))
                .isEqualTo(image.group(1));
    }

    @Test
    void bothGatesSetUpTheNodeNamedInNvmrc() {
        for (String workflow : List.of(".github/workflows/ci.yml", ".github/workflows/release.yml")) {
            assertThat(NODE_VERSION_FILE.matcher(read(ROOT.resolve(workflow))).find())
                    .as("%s sets up Node from .nvmrc", workflow)
                    .isTrue();
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
