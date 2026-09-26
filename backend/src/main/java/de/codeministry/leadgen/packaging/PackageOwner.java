/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.packaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Which database a package folder belongs to.
 *
 * <p>A folder on disk says nothing about who wrote it, and a directory is shared far more
 * easily than a database: a demo stack bind-mounts the same {@code packages/}, a test run reads
 * the same {@code PACKAGES_DIR} from {@code .env}. Asked only "does a row in my database name
 * this folder", every other database answers no for every real package — and that answer
 * deleted them, twice. So each database draws one id ({@code V31}), every {@code meta.json}
 * records it, and anything that deletes a folder asks this class first.
 *
 * <p>The id is read once and kept. It cannot change while the process runs: the row is written
 * by a migration and nothing updates it.
 */
@Component
public class PackageOwner {

    /** The key in {@code meta.json}. */
    static final String KEY = "instance";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final JdbcClient jdbc;
    private volatile String id;

    PackageOwner(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    /** This database's id, as {@code V31} drew it. */
    public String id() {
        String known = id;
        if (known == null) {
            known = jdbc.sql("SELECT id::text FROM instance")
                    .query(String.class)
                    .single();
            id = known;
        }
        return known;
    }

    /**
     * The id a folder's {@code meta.json} records, or empty when it records none — a folder
     * built before {@code V31}, or one that is not a package at all.
     */
    public static Optional<String> of(Path folder) {
        Path meta = folder.resolve("meta.json");
        if (!Files.isRegularFile(meta)) {
            return Optional.empty();
        }
        try {
            JsonNode owner = JSON.readTree(meta.toFile()).path(KEY);
            return owner.isTextual() ? Optional.of(owner.asText()) : Optional.empty();
        } catch (IOException e) {
            // Unreadable is not ours to judge, and therefore not ours to delete.
            return Optional.empty();
        }
    }

    /** Whether this database built the folder. False for a folder that says nothing. */
    public boolean owns(Path folder) {
        return of(folder).map(id()::equals).orElse(false);
    }

    /**
     * Whether another database built the folder. False for a folder that says nothing, which is
     * what separates this from {@code !owns}: a folder from before {@code V31} is not foreign.
     */
    public boolean foreign(Path folder) {
        return of(folder).map(owner -> !owner.equals(id())).orElse(false);
    }
}
