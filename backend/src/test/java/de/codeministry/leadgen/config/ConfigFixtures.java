package de.codeministry.leadgen.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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

    private static final List<String> FILES = List.of(
            ConfigLoader.PIPELINE_FILE,
            ConfigLoader.RULES_FILE,
            ConfigLoader.SOURCES_FILE,
            ConfigLoader.PROFILE_FILE);

    private ConfigFixtures() {}

    /** The repository root, found by walking up rather than from a relative path. */
    public static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null && !Files.isDirectory(candidate.resolve("backend/src/main/resources/leadgen"))) {
            candidate = candidate.getParent();
        }
        if (candidate == null) {
            throw new IllegalStateException(
                    "backend/src/main/resources/leadgen not found above " + Path.of("").toAbsolutePath());
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

    public static ConfigLoader loaderFor(Path directory, jakarta.validation.Validator validator) {
        return loaderFor(directory, validator, Map.of());
    }

    public static ConfigLoader loaderFor(
            Path directory, jakarta.validation.Validator validator, Map<String, String> env) {
        return new ConfigLoader(
                new ConfigProperties(directory.toString(), "./packages", "./data/inbox"),
                validator,
                new PlaceholderResolver(env::get));
    }
}
