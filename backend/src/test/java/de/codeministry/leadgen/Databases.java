/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen;

import org.testcontainers.containers.PostgreSQLContainer;

/**
 * The database every test runs against, named once.
 *
 * <p>It exists because the image is no longer a plain postgres: deduplication compares two
 * adverts that are not identical, which is a vector comparison, and the migration that
 * creates the {@code vector} extension fails on an image that does not ship it — with an
 * error naming the extension rather than the image. The literal was written out in nineteen
 * test classes before this, which is nineteen places for the next change to miss one.
 */
public final class Databases {

    /**
     * The same image {@code docker-compose.yml} runs, and it is the official postgres image
     * with pgvector added. It is Debian-based rather than Alpine, so it is a larger first
     * pull and that is the whole of the cost.
     *
     * <p>Pinned to an exact pgvector and an exact major rather than left floating on
     * {@code pg18}, so that which extension the suite ran against is a value in the diff and
     * not the date somebody last pulled. It has to be changed here and in Compose together.
     *
     * <p>Postgres 18 moved the data directory into a version-scoped path, which is a trap for
     * the Compose file and not for this one: Testcontainers mounts no data volume, so there is
     * no target here that could miss it. The trap is written out beside the mount it applies
     * to.
     */
    public static final String IMAGE = "pgvector/pgvector:0.8.6-pg18";

    private Databases() {}

    /**
     * A container for a test class to declare {@code @Container static final} on. Not shared
     * between classes on purpose: the suite's isolation comes from a database per class, and
     * one container reused across them would make the order of the tests matter.
     */
    public static PostgreSQLContainer<?> postgres() {
        return new PostgreSQLContainer<>(IMAGE);
    }
}
