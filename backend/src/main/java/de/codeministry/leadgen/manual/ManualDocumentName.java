package de.codeministry.leadgen.manual;

import java.nio.file.Path;
import java.util.Locale;

/**
 * The one place a name coming from outside becomes a file name.
 *
 * <p>This is the whole attack surface of the upload endpoint. Everything that reaches the
 * disk goes through {@link #sanitize} on the way in and {@link #resolve} on the way out,
 * so there is exactly one function to read when asking whether a request can write
 * somewhere it should not.
 */
public final class ManualDocumentName {

    /** The only extension accepted, and the only one the `manual-inbox` source globs. */
    public static final String EXTENSION = ".md";

    private static final int MAX_LENGTH = 96;
    private static final String FALLBACK = "offer";

    private ManualDocumentName() {}

    /** Thrown when a name cannot be made safe, or names a file the source would not read. */
    public static class Rejected extends RuntimeException {
        public Rejected(String message) {
            super(message);
        }
    }

    /**
     * Reduces an uploaded name to something that cannot mean anything but a file.
     *
     * <p>Any directory part is dropped rather than cleaned: a name is a name, and the only
     * reason an upload would carry a path is that someone wants it somewhere else.
     * Everything outside a small allowlist becomes a hyphen, which also settles the
     * Unicode questions — no normalization form, no right-to-left override, no NUL.
     */
    public static String sanitize(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new Rejected("the upload states no file name");
        }
        String name = raw.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);

        if (!name.toLowerCase(Locale.ROOT).endsWith(EXTENSION)) {
            // An allowlist and not a denylist: this directory is read by a source that
            // globs *.md, so anything else is a file nothing would ever look at again.
            throw new Rejected("only " + EXTENSION + " documents are accepted, not " + name);
        }

        String stem = name.substring(0, name.length() - EXTENSION.length());
        stem = stem.replaceAll("[^A-Za-z0-9._-]+", "-").replaceAll("^[-.]+", "").replaceAll("[-.]+$", "");
        if (stem.isEmpty()) {
            stem = FALLBACK;
        }
        if (stem.length() > MAX_LENGTH) {
            stem = stem.substring(0, MAX_LENGTH);
        }
        return stem + EXTENSION;
    }

    /**
     * Resolves a sanitized name inside a directory and checks the answer.
     *
     * <p>The check is not redundant. {@link #sanitize} decides what a name may contain and
     * this decides where the result may land, and the two failing together is what a
     * traversal needs. A rule that is only enforced by construction is a rule that stops
     * being enforced the first time construction changes.
     */
    public static Path resolve(Path directory, String name) {
        Path resolved = directory.resolve(sanitize(name)).normalize();
        if (!resolved.getParent().equals(directory.normalize())) {
            throw new Rejected("'" + name + "' resolves outside the inbox");
        }
        return resolved;
    }
}
