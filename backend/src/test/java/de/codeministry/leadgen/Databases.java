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
     * The same image {@code docker-compose.yml} and the Helm chart run, and it is the
     * official postgres image with pgvector added — same version, same data directory, same
     * initdb behaviour. It is Debian-based rather than Alpine, so it is a larger first pull
     * and that is the whole of the cost.
     */
    public static final String IMAGE = "pgvector/pgvector:pg17";

    private Databases() {
    }

    /**
     * A container for a test class to declare {@code @Container static final} on. Not shared
     * between classes on purpose: the suite's isolation comes from a database per class, and
     * one container reused across them would make the order of the tests matter.
     */
    public static PostgreSQLContainer<?> postgres() {
        return new PostgreSQLContainer<>(IMAGE);
    }
}
