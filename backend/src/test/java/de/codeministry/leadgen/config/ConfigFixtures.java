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
import java.util.Collections;
import java.util.LinkedHashMap;
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

    // cover-letter.yaml is left out on purpose: a test that does not write one reads the
    // shipped file from the jar, and ConfigLoaderTest#theCoverLetterStyleShipsRulesAndNoExampleLetter
    // asserts exactly that. Adding it here breaks that test for a reason its message won't name.
    private static final List<String> FILES = List.of(
            ConfigLoader.PIPELINE_FILE, ConfigLoader.RULES_FILE, ConfigLoader.SOURCES_FILE, ConfigLoader.PROFILE_FILE);

    /**
     * Every {@code ${…}} the shipped defaults name, closed with a value of the fixture's own.
     *
     * <p><b>This is the whole of what a test context resolves a placeholder from.</b> The
     * process environment and the developer's {@code .env} are never consulted: a value from
     * either decides a test on one machine and nowhere else, and {@code AUTH_MODE=oidc} in
     * a local file was measured to refuse fifteen MockMvc contexts at startup. The
     * {@code ${LLM_*}} family was closed by hand in one test before this set existed; every
     * other placeholder stayed open.
     *
     * <p>The values are the ones a fresh clone on CI sees — empty, so the placeholder's own
     * default applies and a model is never configured — except the three IMAP credentials,
     * which carry a {@code .invalid} host and a stand-in user and password so that an
     * operator's {@code config/} with an <i>enabled</i> mailbox still binds in
     * {@code OperatorConfigTest}. The shipped mailbox is disabled, so on the defaults the
     * host is never dialled.
     *
     * <p>{@code ConfigFixturesTest} holds this list and the files together in both
     * directions, and {@link #neutralResolver()} refuses a name that is not in it, so a new
     * placeholder in a shipped file fails the build with a message naming this constant
     * rather than resolving from whatever the machine has.
     */
    public static final Map<String, String> NEUTRAL_PLACEHOLDERS = neutralPlaceholders();

    /**
     * Built once per JVM; see {@link #shippedDefaults()}.
     */
    private static Path shippedDefaults;

    private ConfigFixtures() {}

    private static Map<String, String> neutralPlaceholders() {
        Map<String, String> values = new LinkedHashMap<>();
        // pipeline.yaml
        values.put("LLM_PROVIDER", "");
        values.put("LLM_BASE_URL", "");
        values.put("LLM_API_KEY", "");
        values.put("LLM_TIMEOUT", "");
        values.put("LLM_BATCH", "");
        values.put("LLM_MODEL_EXTRACTION", "");
        values.put("LLM_MODEL_SCORING", "");
        values.put("LLM_MODEL_SCORING_OPTIONS", "");
        values.put("LLM_MODEL_WRITING", "");
        values.put("LLM_MODEL_EMBEDDING", "");
        values.put("PROFILE_PATH", "");
        values.put("RULES_PATH", "");
        values.put("RETRIEVAL_ENABLED", "");
        values.put("RETRIEVAL_TOPIC_FLOOR", "");
        values.put("PACKAGES_DIR", "");
        values.put("DIGEST_FORMAT", "");
        values.put("DIGEST_DIR", "");
        values.put("AUTH_MODE", "");
        values.put("OIDC_ISSUER", "");
        values.put("OIDC_CLIENT_ID", "");
        // sources.yaml
        values.put("IMAP_HOST", "imap.invalid");
        values.put("IMAP_PORT", "");
        values.put("IMAP_USER", "someone");
        values.put("IMAP_PASSWORD", "secret");
        values.put("IMAP_PROGRESS_FLAG", "");
        values.put("SAMPLE_FEED_URL", "");
        values.put("INBOX_DIR", "");
        values.put("MANUAL_INBOX_DIR", "");
        return Collections.unmodifiableMap(values);
    }

    /**
     * The resolver every Spring test context gets, registered by
     * {@link NeutralDefaultsInitializer}: {@link #NEUTRAL_PLACEHOLDERS} and nothing behind it.
     *
     * <p>A name the set does not list is an error rather than an empty string. The
     * production resolver is deliberately dumb about that and leaves the judgement to
     * validation; here the judgement is already made — the set is meant to be complete, and
     * a lenient lookup would hand a new placeholder its default in silence, which is exactly
     * the state this fixture exists to end.
     */
    static PlaceholderResolver neutralResolver() {
        return new PlaceholderResolver(name -> {
            if (!NEUTRAL_PLACEHOLDERS.containsKey(name)) {
                throw new IllegalStateException("${" + name + "} is not closed by ConfigFixtures.NEUTRAL_PLACEHOLDERS"
                        + " — add it there with a neutral value, so no test resolves it from the machine it runs on");
            }
            return NEUTRAL_PLACEHOLDERS.get(name);
        });
    }

    /**
     * The repository root, found by walking up rather than from a relative path.
     */
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
