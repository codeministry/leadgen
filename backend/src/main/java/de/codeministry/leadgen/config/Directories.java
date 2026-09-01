package de.codeministry.leadgen.config;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Resolves a configured directory against a working directory that is not one thing.
 *
 * <p>Gradle's `bootRun` runs in `backend/`, an IDE run configuration in the repository
 * root, and a jar wherever it happens to sit. A relative default that is correct in one of
 * them is wrong in the others, and the symptom is an empty directory at a path nobody
 * recognises — which for a source looks exactly like a quiet day on the market.
 */
public final class Directories {

    /** How far up the tree a relative path is searched for. */
    private static final int SEARCH_DEPTH = 4;

    private Directories() {}

    /**
     * An absolute path is taken as given, and so is a relative one that already exists
     * where the process started. The search only ever adds a directory; it never overrides
     * one that is already there.
     *
     * @return the literal reading when nothing is found, so an error names the path the
     *     configuration asked for rather than the last place that was tried.
     */
    public static Path resolve(String path) {
        Path given = Path.of(path);
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
        return given.toAbsolutePath().normalize();
    }

    /**
     * A directory named relative to the configuration directory.
     *
     * <p>The same rule the four YAML files follow: a path in the configuration names a
     * place inside it, not a location on the disk. Resolved against the working directory
     * instead, the very same configuration would point at `backend/…` under `bootRun`, at
     * the repository root in an IDE and at neither from a jar — three empty directories
     * that all look like a source with nothing in it. An absolute path is taken as given,
     * because pointing somewhere else entirely is a legitimate thing to want.
     */
    public static Path under(Path base, String path) {
        Path given = Path.of(path);
        return given.isAbsolute() ? given.normalize() : base.resolve(given).normalize();
    }
}
