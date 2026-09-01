package de.codeministry.leadgen.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;

/**
 * Materializes the shipped examples as a real configuration directory.
 *
 * <p>The tests run against `config/examples/`, not against a fixture of their own, so
 * a broken example fails the build instead of the first user's first start.
 *
 * <p>It lives in this package rather than a shared test package because it builds a
 * {@link ConfigLoader} through the constructor tests use, which is package-private.
 */
public final class ConfigFixtures {

    private ConfigFixtures() {}

    /** The repository root, found by walking up rather than from a relative path. */
    public static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null && !Files.isDirectory(candidate.resolve("config/examples"))) {
            candidate = candidate.getParent();
        }
        if (candidate == null) {
            throw new IllegalStateException("config/examples not found above " + Path.of("").toAbsolutePath());
        }
        return candidate;
    }

    /** Copies every `*.example.yaml` into {@code target} under its real name. */
    public static Path materialize(Path target) {
        Path examples = repositoryRoot().resolve("config/examples");
        try (var files = Files.list(examples)) {
            files.filter(f -> f.getFileName().toString().endsWith(".example.yaml")).forEach(f -> {
                String name = f.getFileName().toString().replace(".example", "");
                try {
                    Files.copy(f, target.resolve(name), StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return target;
    }

    public static ConfigLoader loaderFor(Path directory, jakarta.validation.Validator validator) {
        return loaderFor(directory, validator, Map.of());
    }

    public static ConfigLoader loaderFor(Path directory, jakarta.validation.Validator validator, Map<String, String> env) {
        return new ConfigLoader(
                new ConfigProperties(directory.toString(), "./packages", "./data/inbox"),
                validator,
                new PlaceholderResolver(env::get));
    }
}
