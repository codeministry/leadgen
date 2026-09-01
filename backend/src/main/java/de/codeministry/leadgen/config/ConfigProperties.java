package de.codeministry.leadgen.config;

import jakarta.validation.constraints.NotBlank;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Where the tool's own configuration lives. These are Spring properties — the last thing
 * that is allowed to be wired into the process rather than into a YAML file, because
 * something has to say where the YAML files are.
 */
@Validated
@ConfigurationProperties(prefix = "leadgen")
public record ConfigProperties(@NotBlank String configDir, String packagesDir, String inboxDir) {

    /** How far up the tree a relative default is searched for. */
    private static final int SEARCH_DEPTH = 4;

    /**
     * A relative path is searched for upwards from the working directory, because the
     * working directory is not one thing: Gradle's `bootRun` runs in `backend/`, an IDE
     * run configuration in the repository root, and a jar wherever it happens to sit. A
     * default that is correct in one of them is wrong in the others, and the symptom is a
     * missing file with a path nobody recognises.
     *
     * <p>An absolute path is taken as given, and so is a relative one that exists — the
     * search only ever adds a directory, it never overrides one that is already there.
     */
    public Path configDirectory() {
        Path given = Path.of(configDir);
        if (given.isAbsolute()) {
            return given.normalize();
        }

        Path base = Path.of("").toAbsolutePath();
        for (int i = 0; i <= SEARCH_DEPTH && base != null; i++) {
            Path candidate = base.resolve(given).normalize();
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            base = base.getParent();
        }
        // Nothing found: hand back the literal reading, so the error names the path the
        // configuration actually asked for rather than the last place that was tried.
        return given.toAbsolutePath().normalize();
    }
}
