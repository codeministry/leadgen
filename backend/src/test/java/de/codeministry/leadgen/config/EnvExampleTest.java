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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * {@code .env.example} is the one list of settings an operator reads, so it has to name
 * every environment variable the shipped files read, and nothing they do not.
 *
 * <p>Both directions drift in silence. A placeholder added to a default without a line here
 * is a setting nobody knows they have; a line left behind after its reader went away is a
 * setting somebody configures and nothing obeys. Neither shows up in any other test, because
 * every placeholder carries a default and resolves without complaint.
 *
 * <p>The files are not on the test classpath, so {@code backend/build.gradle.kts} declares
 * them as inputs of {@code test}; without that an edit to {@code .env.example} alone leaves
 * the task UP-TO-DATE and this reports nothing.
 */
class EnvExampleTest {

    private static final Path ROOT = ConfigFixtures.repositoryRoot();

    /**
     * An environment placeholder: {@code ${NAME}}, {@code ${NAME:default}} or Compose's
     * {@code ${NAME:-default}}. Upper case only — {@code ${leadgen.config-dir}} and the like
     * are Spring property references, not environment variables.
     */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Z][A-Z0-9_]+)[}:]");

    /**
     * {@code process.env.NAME} or {@code process.env['NAME']}, the way the dev-server proxy
     * reads its target.
     */
    private static final Pattern PROCESS_ENV = Pattern.compile("process\\.env(?:\\.|\\[['\"])([A-Z][A-Z0-9_]+)");

    private static final Pattern ENV_KEY = Pattern.compile("^([A-Z][A-Z0-9_]*)=", Pattern.MULTILINE);

    /**
     * Keys that exist for the operator's own {@code config/sources.yaml}, which the shipped one
     * does not read — documented in {@code docs/CONFIGURATION.md} under mail access. Named here
     * one by one rather than by scanning the documentation for backticked names, which would
     * let any name that is merely mentioned there pass; and each one is held to that document
     * below, so a stray key cannot be silenced by adding it here alone.
     */
    private static final Set<String> OPERATOR_CONFIG_KEYS =
            Set.of("NEWSLETTER_FOLDER", "NEWSLETTER_FROM", "NEWSLETTER_SUBJECT", "NEWSLETTER_BLOCK_SELECTOR");

    @Test
    void everyOperatorConfigKeyIsDocumented() {
        String documentation = read(ROOT.resolve("docs/CONFIGURATION.md"));
        var undocumented = new TreeSet<String>();
        for (String key : OPERATOR_CONFIG_KEYS) {
            if (!documentation.contains("`" + key + "`")) {
                undocumented.add(key);
            }
        }
        assertThat(undocumented)
                .as("exempted as operator-config but not named in docs/CONFIGURATION.md — document it or drop the line")
                .isEmpty();
    }

    @Test
    void everyPlaceholderHasALine() {
        var missing = new TreeSet<>(placeholders());
        missing.removeAll(envExampleKeys());
        assertThat(missing)
                .as("read by a shipped file but absent from .env.example — add a line with a comment")
                .isEmpty();
    }

    @Test
    void everyLineIsReadBySomething() {
        var stray = new TreeSet<>(envExampleKeys());
        stray.removeAll(placeholders());
        stray.removeAll(OPERATOR_CONFIG_KEYS);
        assertThat(stray)
                .as("in .env.example but read by no shipped file — remove the line, it is a setting nothing obeys")
                .isEmpty();
    }

    private static Set<String> placeholders() {
        var names = new TreeSet<String>();
        for (Path file : scannedFiles()) {
            String content = read(file);
            collect(PLACEHOLDER, content, names);
            collect(PROCESS_ENV, content, names);
        }
        return names;
    }

    /**
     * The five shipped defaults, the Spring configuration, Compose and the dev-server proxy.
     */
    private static List<Path> scannedFiles() {
        var files = new ArrayList<Path>();
        try (Stream<Path> defaults = Files.list(ROOT.resolve("backend/src/main/resources/leadgen"))) {
            defaults.filter(file -> file.getFileName().toString().endsWith(".yaml"))
                    .sorted()
                    .forEach(files::add);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        assertThat(files).as("the shipped defaults under resources/leadgen").isNotEmpty();
        files.add(ROOT.resolve("backend/src/main/resources/application.yaml"));
        files.add(ROOT.resolve("docker-compose.yml"));
        files.add(ROOT.resolve("frontend/proxy.conf.js"));
        return files;
    }

    private static Set<String> envExampleKeys() {
        var keys = new TreeSet<String>();
        collect(ENV_KEY, read(ROOT.resolve(".env.example")), keys);
        return keys;
    }

    private static void collect(Pattern pattern, String content, Set<String> into) {
        Matcher matcher = pattern.matcher(content);
        while (matcher.find()) {
            into.add(matcher.group(1));
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
