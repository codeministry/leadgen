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
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DotEnvTest {

    @TempDir
    Path directory;

    private DotEnv write(String content) throws IOException {
        Path file = directory.resolve(DotEnv.FILE_NAME);
        Files.writeString(file, content);
        return new DotEnv(java.util.Optional.of(file), DotEnv.parse(file));
    }

    @Test
    void readsAssignments() throws IOException {
        DotEnv dotenv = write("""
            # a comment
            IMAP_HOST=imap.example.org
            IMAP_PORT=993   # a trailing comment is not part of the value
            DIGEST_DIR="./with spaces"
            EMPTY=
            """);

        assertThat(dotenv.values())
                .containsEntry("IMAP_HOST", "imap.example.org")
                .containsEntry("IMAP_PORT", "993")
                .containsEntry("DIGEST_DIR", "./with spaces");
    }

    @Test
    void anEmptyAssignmentIsNoValueButIsStillDeclared() throws IOException {
        DotEnv dotenv = write("EMPTY=\nSET=value\n");

        // The resolver must not see it, so the default in the YAML still applies...
        assertThat(dotenv.values()).doesNotContainKey("EMPTY");
        // ...and the banner must, because "declared and empty" is what someone is looking for.
        assertThat(dotenv.declared()).containsEntry("EMPTY", "");
    }

    @Test
    void theBuildTellsTheReaderToStandDownByTheNameTheReaderLooksFor() throws IOException {
        // `processAot` starts the real context, so whatever this class finds at build time is
        // frozen into the jar as a set of condition outcomes. The only thing that stops it is
        // the switch, and the switch is only worth having while both halves spell it the same
        // way: rename one, and a local `./gradlew bootJar` silently reads `.env` again with
        // nothing in the diff to say so.
        String build = Files.readString(ConfigFixtures.repositoryRoot().resolve("backend/build.gradle.kts"));

        assertThat(build).contains("environment(\"" + DotEnv.SKIP + "\"");
    }

    @Test
    void keepsFileOrder() throws IOException {
        DotEnv dotenv = write("Z=1\nA=2\nM=3\n");

        assertThat(dotenv.declared().keySet()).containsExactly("Z", "A", "M");
    }
}
