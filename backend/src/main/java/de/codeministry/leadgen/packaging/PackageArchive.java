/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.packaging;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * The one place a stored {@code package_dir} becomes a directory this application will
 * read from. Same shape and same reason as {@code manual/ManualDocumentName}: one function
 * decides what the value may mean, another decides where the result may land, and both run
 * on every path — a rule enforced only by construction stops being enforced the first time
 * construction changes.
 *
 * <p>Only the last segment of the stored value is used, and dropping the directory part is
 * not merely defensive here. {@link PackagingService} stores
 * {@code Path.of(outputDir).resolve(name)}, so a row written inside the container holds
 * {@code /packages/…} while a process on the host reads {@code ./packages}. Resolving by
 * folder name makes both work, and a folder that is genuinely gone becomes a clean refusal
 * rather than a path outside the directory the process was pointed at.
 */
public final class PackageArchive {

    private PackageArchive() {}

    /** Thrown when a stored value names no package this application is allowed to read. */
    public static class Rejected extends RuntimeException {
        public Rejected(String message) {
            super(message);
        }
    }

    /** The folder a stored {@code package_dir} names, with any directory part dropped. */
    public static String folderName(String packageDir) {
        if (packageDir == null || packageDir.isBlank()) {
            throw new Rejected("no package folder is recorded");
        }
        String name = packageDir.replace('\\', '/');
        while (name.endsWith("/")) {
            name = name.substring(0, name.length() - 1);
        }
        name = name.substring(name.lastIndexOf('/') + 1);
        if (name.isBlank() || ".".equals(name) || "..".equals(name)) {
            throw new Rejected("'" + packageDir + "' does not name a package folder");
        }
        return name;
    }

    /**
     * Resolves the folder inside the configured output directory and checks the answer.
     *
     * <p>The containment check is not redundant against {@link #folderName}. That one says
     * what a value may contain, this one says where the result may be, and a traversal
     * needs both to fail together.
     */
    public static Path resolve(Path outputDir, String packageDir) {
        Path root = outputDir.toAbsolutePath().normalize();
        Path resolved = root.resolve(folderName(packageDir)).normalize();
        if (!root.equals(resolved.getParent())) {
            throw new Rejected("'" + packageDir + "' resolves outside " + root);
        }
        if (!Files.isDirectory(resolved)) {
            throw new Rejected("the package folder '" + resolved + "' does not exist");
        }
        return resolved;
    }

    /**
     * Streams the folder as a zip, entries named relative to it.
     *
     * <p>Regular files only, and symlinks are not followed: what leaves is what the
     * packaging stage put there, never something it happens to point at.
     */
    public static void writeZip(Path folder, OutputStream out) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8);
                Stream<Path> walk = Files.walk(folder)) {
            List<Path> files = walk.filter(file -> Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
                    .sorted()
                    .toList();
            for (Path file : files) {
                zip.putNextEntry(new ZipEntry(folder.relativize(file).toString().replace('\\', '/')));
                Files.copy(file, zip);
                zip.closeEntry();
            }
            zip.finish();
        }
    }
}
