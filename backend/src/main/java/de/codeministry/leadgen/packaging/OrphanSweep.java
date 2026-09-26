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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Removes package folders nothing points at any more.
 *
 * <p>Three things leave one behind, and none of them can clean up after itself. A build that
 * died between {@code Files.createDirectories} and the {@code UPDATE} never recorded where it
 * wrote. A discard that could not delete the directory still cleared the row. And {@code V27}
 * cleared seventy-odd rows in one statement, because a migration has no disk.
 *
 * <p><b>A folder is only removed when its {@code meta.json} names this database.</b> The file
 * alone was the first safety catch — it keeps a misconfigured path from emptying a directory
 * of somebody's files — and it was not enough. The output directory is shared far more
 * easily than a database: a demo stack bind-mounts the same {@code packages/}, a test run
 * reads the same {@code PACKAGES_DIR} from {@code .env}. Asked only "does a row of mine name
 * it", every one of those databases answered no for every real package, and deleted it:
 * 76 folders on 2026-09-19, none left a week later. {@link PackageOwner} is the second catch,
 * and the one that decides. A folder from before {@code V31}, which names no database, is
 * never swept; clear those by hand once. Direct children only, for the same reason
 * {@link PackageArchive#resolve} accepts nothing else.
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
    private final PackageOwner owner;

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
                    // Ours, provably: the folder names this database. A folder another database
                    // built, or one from before V31 that names none, is never swept. "No row of
                    // mine names it" alone is what emptied a shared directory twice.
                    .filter(owner::owns)
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
