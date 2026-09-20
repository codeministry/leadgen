/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.packaging;

import de.codeministry.leadgen.application.ApplicationStatus;
import de.codeministry.leadgen.config.ConfigRegistry;
import de.codeministry.leadgen.config.Directories;
import de.codeministry.leadgen.config.model.PipelineConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/**
 * Reads a finished package folder back out, as one file.
 *
 * <p>The package is and stays a folder on the machine that ran the pipeline — that is what
 * {@link PackagingService} builds and what its documentation describes. This only bundles
 * it so the operator can fetch it from a browser that is not on that machine. It is the
 * same act as opening the folder in a file manager, and deliberately not a send path: there
 * is no recipient here, no channel and no address, which is what {@code NothingIsSentTest}
 * reads the repository for.
 */
@Service
@Slf4j
public class PackageArchiveService {

    /**
     * The packages among a set of offers that may be thrown away.
     *
     * <p><b>"Was it ever sent" is mostly a question only the event log can answer.</b> The
     * current status alone cannot: a LOST application may have been answered and lost, or
     * written off before anybody wrote a line, and those two have opposite answers here. A
     * folder that went out documents what was actually sent and is kept whatever happens to
     * the offer afterwards; one that never left is a rebuildable artefact and goes.
     *
     * <p>The current status is asked as well, and it is not redundant. Deleting is the
     * irreversible half of this, so the conservative reading wins: a row standing at SENT
     * with no event behind it — opened there directly, or written by hand — must not lose
     * its folder because the log has a hole in it.
     *
     * <p>An offer with no application row at all has trivially never been sent, which is why
     * both halves are a {@code NOT EXISTS} rather than a join.
     */
    private static final String DISCARDABLE = """
        SELECT o.id, o.package_dir
        FROM offer o
        WHERE o.id = ANY (?)
          AND o.package_dir IS NOT NULL
          AND NOT EXISTS (
              SELECT 1 FROM application a WHERE a.offer_id = o.id AND a.status = ANY (?))
          AND NOT EXISTS (
              SELECT 1 FROM application a
              JOIN application_event e ON e.application_id = a.id
              WHERE a.offer_id = o.id AND e.to_status = ANY (?))
        """;

    /**
     * The states that mean the mail has left; decided by the enum, not here.
     */
    private static final String[] OUT = Arrays.stream(ApplicationStatus.values())
        .filter(ApplicationStatus::isOut)
        .map(Enum::name)
        .toArray(String[]::new);

    private final ConfigRegistry config;
    private final JdbcClient jdbc;

    PackageArchiveService(ConfigRegistry config, DataSource dataSource) {
        this.config = config;
        this.jdbc = JdbcClient.create(dataSource);
    }

    /**
     * The folder built for one offer, or empty when the offer has none.
     *
     * <p>Empty is the honest and common answer: a package exists only for what cleared the
     * shortlist threshold, so most offers never get one.
     */
    public Optional<Path> folderFor(long offerId) {
        List<String> stored = jdbc.sql("SELECT package_dir FROM offer WHERE id = ?")
                .param(offerId)
                .query(String.class)
                .list();
        return stored.stream()
                .filter(dir -> dir != null && !dir.isBlank())
                .findFirst()
                .map(dir -> PackageArchive.resolve(outputDirectory(), dir));
    }

    /**
     * Throws away the packages of offers that are leaving the working list.
     *
     * <p>A folder costs disk for as long as it exists and is worth exactly nothing once
     * nobody is going to send it — and it is rebuildable, which is the whole reason this is
     * safe: moving the offer back to PACKAGED builds it again from the same advert. Nulling
     * {@code packaged_at} is what re-arms that.
     *
     * <p><b>Only the manual archive calls this.</b> The age pass reconciles rather than
     * decides — {@code RESTORE_INSIDE_WINDOW} brings its own rows back the moment the
     * freshness window widens — and a pass that undoes itself must not delete files on the
     * way. It cannot reach a prepared offer anyway: {@link ApplicationStatus#isLive()}
     * exempts everything from NEW upwards.
     *
     * <p>A folder that is already gone is not a failure. The row is cleaned up either way,
     * which is what makes this safe to run twice over the same ids.
     *
     * @return how many offers lost their package.
     */
    @Transactional
    public int discard(Collection<Long> offerIds) {
        Long[] ids = offerIds.stream().distinct().toArray(Long[]::new);
        if (ids.length == 0) {
            return 0;
        }
        List<Discardable> rows = jdbc.sql(DISCARDABLE)
            .param(ids)
            .param(OUT)
            .param(OUT)
            .query((rs, row) -> new Discardable(rs.getLong("id"), rs.getString("package_dir")))
            .list();
        if (rows.isEmpty()) {
            return 0;
        }

        Path root = outputDirectory();
        List<Long> cleared = new ArrayList<>();
        for (Discardable row : rows) {
            try {
                PackageArchive.deleteFolder(PackageArchive.resolve(root, row.packageDir()));
            } catch (PackageArchive.Rejected e) {
                // Already gone, or never where the row says. The reference is the thing that
                // has to go either way — leaving it would keep a download link that 404s.
                log.info("Offer {} has no package folder to discard: {}", row.id(), e.getMessage());
            } catch (IOException e) {
                // Loud, and not fatal: the rest of the set is a set of independent decisions,
                // and a folder nobody could delete is better reported than silently kept.
                log.error("The package folder of offer {} could not be removed: {}", row.id(), e.getMessage(), e);
                continue;
            }
            cleared.add(row.id());
        }

        if (cleared.isEmpty()) {
            return 0;
        }
        jdbc.sql("UPDATE offer SET package_dir = NULL, packaged_at = NULL, language = NULL WHERE id = ANY (?)")
            .param(cleared.toArray(Long[]::new))
            .update();
        log.info("{} of {} archived offers lost their package", cleared.size(), ids.length);
        return cleared.size();
    }

    /**
     * One offer whose package may go, as {@link #DISCARDABLE} returns it.
     */
    private record Discardable(long id, String packageDir) {
    }

    /**
     * The same directory the packaging stage writes into, resolved the way every other
     * relative path in this application is: upwards from a working directory that is not
     * one thing.
     */
    private Path outputDirectory() {
        PipelineConfig.Packaging settings = config.snapshot().application().packaging();
        if (settings == null) {
            throw new PackageArchive.Rejected("no packaging output directory is configured");
        }
        return Directories.resolve(settings.outputDir());
    }
}
