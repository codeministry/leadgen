/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.config;

import java.nio.file.Path;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;

/**
 * Resolves `${VAR}` and `${VAR:default}` in the raw YAML text, before it is parsed.
 *
 * <p><b>Values come from the process environment first and from `.env` second</b>,
 * and the file is found by searching upwards from the working directory. That is what makes
 * the tool behave the same however it was started: Gradle's `bootRun` runs in `backend/`,
 * an IDE run configuration in the repository root, a jar wherever it sits, and Compose
 * passes real environment variables. Reading the file here rather than in the build means
 * no start path is privileged — the earlier version loaded it in a `bootRun` hook, so
 * launching the very same configuration from an IDE silently saw none of it.
 *
 * <p>Deliberately dumb about what it finds: an unresolved placeholder without a default
 * becomes an empty string rather than an error. Whether an empty value is acceptable is a
 * question about the field, not about the environment — an LLM key may be missing (the tool
 * runs without a model), the IMAP host of an enabled source may not. That judgement belongs
 * to validation, which can see which source is enabled.
 *
 * <p>Resolution runs on the text and not on the parsed tree because a placeholder may sit
 * anywhere, including inside a key or in a quoted regex. The pattern excludes `}` from the
 * variable name, so a regex like {@code (\d{1,3})} — braces but no `${` — is never touched.
 */
@Slf4j
final class PlaceholderResolver {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)(?::([^}]*))?}");

    private final UnaryOperator<String> environment;

    PlaceholderResolver(UnaryOperator<String> environment) {
        this.environment = environment;
    }

    /** The process environment, with `.env` behind it. A real variable always wins. */
    static PlaceholderResolver fromSystemEnvironment() {
        DotEnv dotenv = DotEnv.load();
        dotenv.file()
                .ifPresentOrElse(
                        file -> log.info("Reading {} for configuration values", file),
                        () -> log.info(
                                "No {} found above {} — only real environment variables apply",
                                DotEnv.FILE_NAME,
                                Path.of("").toAbsolutePath()));

        Map<String, String> file = dotenv.values();
        return new PlaceholderResolver(name -> {
            String fromProcess = System.getenv(name);
            return fromProcess != null && !fromProcess.isBlank() ? fromProcess : file.get(name);
        });
    }

    String resolve(String raw) {
        Matcher matcher = PLACEHOLDER.matcher(raw);
        StringBuilder out = new StringBuilder();

        while (matcher.find()) {
            String name = matcher.group(1);
            String fallback = matcher.group(2);
            String value = environment.apply(name);

            if (value == null || value.isBlank()) {
                // An empty value is a non-statement, not a statement of "empty": a copied
                // template carries `IMAP_USER=`, and letting that beat the default would
                // break the run for no reason.
                value = fallback != null ? fallback : "";
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(out);
        return out.toString();
    }
}
