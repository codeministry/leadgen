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

import de.codeministry.leadgen.config.ConfigFixtures;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;

/**
 * Every shipped default is reachable under a name computed at runtime.
 *
 * <p>Asserted against the directory rather than against a list, because a list is what fails:
 * the next `.yaml` or `.ftl` added under `src/main/resources/leadgen/` gets no hint, the JVM
 * image does not care, and the native one answers the empty `Optional` that also means "the
 * operator did not override this file". This test is the only place that difference is visible
 * without compiling an image.
 */
class LeadGenRuntimeHintsTest {

    @Test
    void everyShippedDefaultIsReachableByAComputedName() throws IOException {
        RuntimeHints hints = new RuntimeHints();
        new LeadGenRuntimeHints().registerHints(hints, getClass().getClassLoader());

        Path shipped = ConfigFixtures.repositoryRoot().resolve("backend/src/main/resources/leadgen");
        List<String> resources;
        try (Stream<Path> files = Files.walk(shipped)) {
            resources = files.filter(Files::isRegularFile)
                    .map(file -> shipped.getParent().relativize(file).toString().replace('\\', '/'))
                    .sorted()
                    .toList();
        }

        // Guards the guard: a wrong path would make every assertion below vacuous.
        assertThat(resources).hasSizeGreaterThanOrEqualTo(7);
        assertThat(resources)
                .allSatisfy(
                        resource -> assertThat(RuntimeHintsPredicates.resource().forResource(resource))
                                .as(resource)
                                .accepts(hints));
    }
}
