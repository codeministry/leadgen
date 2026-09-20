/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.packaging;

import de.codeministry.leadgen.config.ConfigFixtures;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The shipped defaults with a packages directory of this test's own.
 *
 * <p><b>Built once and remembered</b>, because a {@code @DynamicPropertySource} supplier is
 * called every time the property is resolved and not once per context — anything with a side
 * effect in it therefore happens again the moment something else reads
 * {@code leadgen.config-dir}, leaving the test looking at a directory the application never
 * writes to. {@code PackagingServiceTest} carries its own copy of this because it also has to
 * rewrite the CV variants; everything else that needs a real output directory uses this one.
 */
final class PackagesFixture {

    private PackagesFixture() {
    }

    private static Path configDirectory;
    private static Path packagesDirectory;

    static synchronized Path config() {
        materialize();
        return configDirectory;
    }

    static synchronized Path packages() {
        materialize();
        return packagesDirectory;
    }

    private static void materialize() {
        if (configDirectory != null) {
            return;
        }
        try {
            Path dir = Files.createTempDirectory("leadgen-packaging-config");
            dir.toFile().deleteOnExit();
            Path packages = Files.createTempDirectory("leadgen-packages");
            packages.toFile().deleteOnExit();
            ConfigFixtures.materialize(dir);

            Path pipeline = dir.resolve("pipeline.yaml");
            Files.writeString(
                pipeline,
                Files.readString(pipeline, StandardCharsets.UTF_8)
                    .replace("output_dir: ${PACKAGES_DIR:./packages}", "output_dir: " + packages),
                StandardCharsets.UTF_8);

            packagesDirectory = packages;
            configDirectory = dir;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * A package folder as the packaging stage leaves one: a directory with a {@code meta.json}
     * in it, which is what marks it as ours.
     */
    static Path aPackage(String name) {
        try {
            Path folder = Files.createDirectories(packages().resolve(name));
            Files.writeString(folder.resolve("meta.json"), "{\"offerId\":0}", StandardCharsets.UTF_8);
            Files.writeString(folder.resolve("cover_letter.txt"), "Sehr geehrte Damen und Herren", StandardCharsets.UTF_8);
            return folder;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
