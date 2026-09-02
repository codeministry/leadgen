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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;

/**
 * The `.env` file: where it is and what it declares.
 *
 * <p><b>One reader, two audiences.</b> {@link PlaceholderResolver} needs the values, the
 * startup banner needs the file's own picture of itself — including the keys that are
 * declared and empty, which are precisely the ones behind "the value is in the file and
 * the service says it is missing". A second parser somewhere else would answer that
 * question differently the first time either changed.
 *
 * <p><b>Declared and effective are not the same thing.</b> {@link #declared()} keeps every
 * assignment in file order, empty ones included; {@link #values()} drops the empty ones,
 * because an empty assignment is a non-statement and the default in the YAML still applies.
 *
 * <p>The file is searched upwards from the working directory for the same reason the config
 * directory is: Gradle's `bootRun` runs in `backend/`, an IDE run configuration in the
 * repository root, a jar wherever it sits.
 */
@Slf4j
public record DotEnv(Optional<Path> file, Map<String, String> declared) {

    /** The credentials file, and how far up it is looked for. */
    public static final String FILE_NAME = ".env";

    private static final int SEARCH_DEPTH = 4;

    /** Locates and reads the file, or reports that there is none. */
    public static DotEnv load() {
        Path base = Path.of("").toAbsolutePath();
        for (int i = 0; i <= SEARCH_DEPTH && base != null; i++) {
            Path candidate = base.resolve(FILE_NAME);
            if (Files.isRegularFile(candidate)) {
                return new DotEnv(Optional.of(candidate), parse(candidate));
            }
            base = base.getParent();
        }
        return new DotEnv(Optional.empty(), Map.of());
    }

    /** The assignments that carry a value. This is what resolves a `${VAR}`. */
    public Map<String, String> values() {
        Map<String, String> values = new LinkedHashMap<>();
        declared.forEach((key, value) -> {
            if (!value.isEmpty()) {
                values.put(key, value);
            }
        });
        return values;
    }

    /** Every assignment in the file, in file order, empty values included. */
    static Map<String, String> parse(Path file) {
        Map<String, String> values = new LinkedHashMap<>();
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#") || !trimmed.contains("=")) {
                    continue;
                }
                String key = trimmed.substring(0, trimmed.indexOf('=')).trim();
                // A trailing comment is not part of the value, and a quoted value keeps its
                // spaces. Neither is exotic: the shipped template uses both.
                String raw = trimmed.substring(trimmed.indexOf('=') + 1).trim();
                if (raw.startsWith("\"") && raw.endsWith("\"") && raw.length() > 1) {
                    raw = raw.substring(1, raw.length() - 1);
                } else if (raw.contains("#")) {
                    raw = raw.substring(0, raw.indexOf('#')).trim();
                }
                values.put(key, raw);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
        return values;
    }
}
