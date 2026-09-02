/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Materializes the shipped defaults as an external configuration directory.
 *
 * <p>The tests run against the files in `backend/src/main/resources/leadgen/`, not against
 * fixtures of their own, so a broken default fails the build instead of the first user's
 * first start.
 *
 * <p>It lives in this package rather than a shared test package because it builds a
 * {@link ConfigLoader} through the constructor tests use, which is package-private.
 */
public final class ConfigFixtures {

    private static final List<String> FILES = List.of(
            ConfigLoader.PIPELINE_FILE, ConfigLoader.RULES_FILE, ConfigLoader.SOURCES_FILE, ConfigLoader.PROFILE_FILE);

    /** Built once per JVM; see {@link #shippedDefaults()}. */
    private static Path shippedDefaults;

    private ConfigFixtures() {}

    /** The repository root, found by walking up rather than from a relative path. */
    public static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null && !Files.isDirectory(candidate.resolve("backend/src/main/resources/leadgen"))) {
            candidate = candidate.getParent();
        }
        if (candidate == null) {
            throw new IllegalStateException("backend/src/main/resources/leadgen not found above "
                    + Path.of("").toAbsolutePath());
        }
        return candidate;
    }

    /**
     * Writes the classpath defaults into {@code target} as real files, so a test can edit
     * one and watch the external layer override it.
     */
    public static Path materialize(Path target) {
        FILES.forEach(name -> {
            var source = ConfigSource.fromClasspath(name)
                    .orElseThrow(() -> new IllegalStateException("no default ships for " + name));
            try {
                Files.writeString(target.resolve(name), source.content(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        return target;
    }

    /**
     * The shipped defaults as an external configuration directory, built once per JVM.
     *
     * <p>A {@code @SpringBootTest} that does not say which configuration directory it wants
     * gets the developer's own {@code config/} through {@code .env} — so a threshold lowered
     * on one machine fails the build there and nowhere else, and passes everywhere it is not
     * looked at. Same class as the {@code .env} trap on the keyless scoring path: what is
     * under test is the code, not whose machine it runs on.
     *
     * <p>Built once and remembered. A {@code @DynamicPropertySource} supplier is called every
     * time the property is resolved and not once per context, so a fresh directory per
     * resolution would give one context two configurations.
     */
    public static synchronized Path shippedDefaults() {
        if (shippedDefaults == null) {
            try {
                Path dir = Files.createTempDirectory("leadgen-defaults");
                dir.toFile().deleteOnExit();
                shippedDefaults = materialize(dir);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return shippedDefaults;
    }

    public static ConfigLoader loaderFor(Path directory, jakarta.validation.Validator validator) {
        return loaderFor(directory, validator, Map.of());
    }

    public static ConfigLoader loaderFor(
            Path directory, jakarta.validation.Validator validator, Map<String, String> env) {
        return new ConfigLoader(
                new ConfigProperties(directory.toString()), validator, new PlaceholderResolver(env::get));
    }
}
