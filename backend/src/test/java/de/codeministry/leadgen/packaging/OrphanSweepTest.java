/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.packaging;

import de.codeministry.leadgen.Databases;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The folders nothing points at any more.
 *
 * <p>Three things leave one behind and none of them can clean up after itself: a build that
 * died before it recorded where it wrote, a discard that cleared the row and could not
 * delete the directory, and {@code V27}, which cleared seventy-odd rows in one statement
 * because a migration has no disk.
 */
@SpringBootTest
@Testcontainers
class OrphanSweepTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = Databases.postgres();

    @DynamicPropertySource
    static void configuration(DynamicPropertyRegistry registry) {
        registry.add("leadgen.config-dir", () -> PackagesFixture.config().toString());
    }

    @Autowired
    private OrphanSweep sweep;

    @Autowired
    private JdbcTemplate jdbc;

    private long sourceId;

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM application");
        jdbc.update("DELETE FROM offer");
        jdbc.update("DELETE FROM source");
        sourceId =
            jdbc.queryForObject("INSERT INTO source (name, kind) VALUES ('test', 'file') RETURNING id", Long.class);
    }

    @Test
    void removesAPackageFolderNoRowNames() {
        Path orphan = PackagesFixture.aPackage("2026-09-02_acme_verwaist");

        sweep.run(null);

        assertThat(Files.exists(orphan)).isFalse();
    }

    @Test
    void keepsAFolderAnOfferStillPointsAt() {
        Path kept = PackagesFixture.aPackage("2026-09-02_acme_referenziert");
        offerPointingAt(kept);

        sweep.run(null);

        assertThat(Files.exists(kept)).isTrue();
    }

    @Test
    void matchesByFolderNameAndNotByTheStoredPath() {
        // The container writes `/packages/…` and a process on the host reads a temp
        // directory, so the stored prefix is the one part of the value that cannot be
        // trusted. Matching on it would sweep every folder on a machine that moved.
        Path kept = PackagesFixture.aPackage("2026-09-02_acme_anderer-pfad");
        offerPointingAt(Path.of("/packages").resolve(kept.getFileName()));

        sweep.run(null);

        assertThat(Files.exists(kept)).isTrue();
    }

    @Test
    void leavesADirectoryThatIsNotOneOfOursAlone() throws IOException {
        // The safety catch. The output directory comes from configuration and this runs
        // unattended at every start, so a misconfigured path has to find nothing it
        // recognises rather than a directory full of somebody's files. `meta.json` is
        // written for every package and by nothing else.
        Path foreign = Files.createDirectories(PackagesFixture.packages().resolve("jemandes-ordner"));
        Files.writeString(foreign.resolve("wichtig.txt"), "bleibt", StandardCharsets.UTF_8);

        sweep.run(null);

        assertThat(Files.exists(foreign.resolve("wichtig.txt"))).isTrue();
    }

    private void offerPointingAt(Path folder) {
        jdbc.update(
            """
                INSERT INTO offer (source_id, external_id, title, url, fingerprint, status, package_dir, packaged_at)
                VALUES (?, ?, 'Referenziert', 'https://example.invalid/x', ?, 'PASSED', ?, now())
                """,
            sourceId,
            folder.getFileName().toString(),
            folder.getFileName().toString(),
            folder.toString());
    }
}
