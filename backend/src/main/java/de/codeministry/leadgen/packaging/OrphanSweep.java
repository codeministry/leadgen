/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.packaging;

import de.codeministry.leadgen.config.ConfigRegistry;
import de.codeministry.leadgen.config.Directories;
import de.codeministry.leadgen.config.model.PipelineConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Removes package folders nothing points at any more.
 *
 * <p>Three things leave one behind, and none of them can clean up after itself. A build that
 * died between {@code Files.createDirectories} and the {@code UPDATE} never recorded where it
 * wrote. A discard that could not delete the directory still cleared the row. And {@code V27}
 * cleared seventy-odd rows in one statement, because a migration has no disk.
 *
 * <p><b>A folder is only removed when it carries a {@code meta.json}.</b> That file is written
 * by {@link PackagingService} for every package and by nothing else, so it is the marker that
 * says "this is ours". It is the safety catch that matters here: the output directory comes
 * from configuration, this runs unattended at every start, and a misconfigured path must find
 * nothing it recognises rather than a directory full of somebody's files. Direct children
 * only, for the same reason {@link PackageArchive#resolve} accepts nothing else.
 *
 * <p>It runs on every start rather than once, and on a healthy instance it reports nothing.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class OrphanSweep implements ApplicationRunner {

    /**
     * Written for every package and by nothing else. A directory without it is not ours.
     */
    private static final String MARKER = "meta.json";

    private final ConfigRegistry config;
    private final DataSource dataSource;

    @Override
    public void run(ApplicationArguments args) {
        try {
            sweep();
        } catch (RuntimeException | IOException e) {
            // Never fatal. A folder nobody could delete is disk, and refusing to start over
            // it would take the whole tool down for the one thing it can do without.
            log.warn("The package folders could not be swept: {}", e.getMessage(), e);
        }
    }

    private void sweep() throws IOException {
        PipelineConfig.Packaging settings = config.snapshot().application().packaging();
        if (settings == null) {
            return;
        }
        Path root = Directories.resolve(settings.outputDir());
        if (!Files.isDirectory(root)) {
            return;
        }

        Set<String> referenced = new HashSet<>();
        for (String stored : JdbcClient.create(dataSource)
            .sql("SELECT package_dir FROM offer WHERE package_dir IS NOT NULL")
            .query(String.class)
            .list()) {
            try {
                referenced.add(PackageArchive.folderName(stored));
            } catch (PackageArchive.Rejected e) {
                // A stored value that names no folder cannot protect one either.
                log.debug("Ignoring an unreadable package_dir while sweeping: {}", e.getMessage());
            }
        }

        List<Path> orphans;
        try (Stream<Path> children = Files.list(root)) {
            orphans = children.filter(Files::isDirectory)
                .filter(folder -> Files.isRegularFile(folder.resolve(MARKER)))
                .filter(folder -> !referenced.contains(folder.getFileName().toString()))
                .sorted()
                .toList();
        }
        if (orphans.isEmpty()) {
            return;
        }

        int removed = 0;
        for (Path orphan : orphans) {
            try {
                PackageArchive.deleteFolder(orphan);
                log.info("Removed the orphaned package folder {}", orphan.getFileName());
                removed++;
            } catch (IOException e) {
                log.warn("The orphaned package folder {} could not be removed: {}", orphan, e.getMessage());
            }
        }
        log.info("Swept {} of {} orphaned package folder(s) from {}", removed, orphans.size(), root);
    }
}
