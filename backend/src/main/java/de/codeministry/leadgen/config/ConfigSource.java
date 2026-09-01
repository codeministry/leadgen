package de.codeministry.leadgen.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * One configuration file, and where it came from.
 *
 * <p>The defaults ship on the classpath under {@code /leadgen/} and an external directory
 * overrides them file by file. That is Spring Boot's own layering, applied to files Spring
 * does not read: a working default is in the jar, and anything individual sits outside it.
 *
 * <p>The classpath directory is {@code /leadgen/} and deliberately not {@code /config/} —
 * Spring scans {@code classpath:/config/} for its own configuration by default, so a file
 * placed there would be read twice, once by this loader and once by Spring, which would
 * quietly bind whatever happened to match.
 *
 * @param origin where the content was read from, for the log line and the error message
 * @param onDisk the file, when it came from the external directory. A classpath default has
 *     no path, which is also why it cannot be hot-reloaded.
 */
public record ConfigSource(String name, String origin, Optional<Path> onDisk, String content) {

    private static final String CLASSPATH_DIRECTORY = "/leadgen/";

    /** External first, classpath second. Empty when neither has the file. */
    public static Optional<ConfigSource> resolve(Path externalDirectory, String name) {
        Path external = externalDirectory.resolve(name);
        if (Files.isRegularFile(external)) {
            return Optional.of(new ConfigSource(name, external.toString(), Optional.of(external), read(external)));
        }
        return fromClasspath(name);
    }

    public static Optional<ConfigSource> fromClasspath(String name) {
        String resource = CLASSPATH_DIRECTORY + name;
        try (InputStream in = ConfigSource.class.getResourceAsStream(resource)) {
            if (in == null) {
                return Optional.empty();
            }
            return Optional.of(new ConfigSource(
                    name, "classpath:" + resource, Optional.empty(), new String(in.readAllBytes(), StandardCharsets.UTF_8)));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + resource, e);
        }
    }

    public boolean isDefault() {
        return onDisk.isEmpty();
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
    }
}
