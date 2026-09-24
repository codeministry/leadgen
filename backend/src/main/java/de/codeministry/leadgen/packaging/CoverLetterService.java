/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.packaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.codeministry.leadgen.application.ApplicationStatus;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * The cover letter of a package, after the build: read it, save a person's version, or write a
 * fresh draft.
 *
 * <p><b>The file is what counts, and it is written first.</b> {@code cover_letter.txt} is what
 * the package download serves; the row is the copy the offer detail view reads and what a
 * rebuild checks before it would draft. Every write goes file, then {@code meta.json}, then row,
 * so a crash leaves the file newer than the row and the next save or rebuild writes both again.
 *
 * <p><b>From SENT on the letter is frozen.</b> Saving and drafting are refused while the
 * application stands at SENT or any state after it, and allowed at NEW, SHORTLISTED and
 * PACKAGED. The current status decides, not the event log: an application moved back from SENT,
 * or restored to NEW after an archive, is being prepared again, and its letter with it. The log
 * still decides what an archive keeps — that is {@link PackageArchiveService#wentOut}, and it is
 * a different question.
 *
 * <p><b>Deliberately not {@code @Transactional}.</b> A draft waits on the writing model, and a
 * transaction around it would hold a connection for as long as the model takes. The row is one
 * {@code UPDATE}, which is atomic on its own.
 */
@Slf4j
@Service
public class CoverLetterService {

    private final PackagingService packaging;
    private final PackageArchiveService packages;
    private final JdbcClient jdbc;
    private final JsonMapper json = JsonMapper.builder().build();

    CoverLetterService(PackagingService packaging, PackageArchiveService packages, DataSource dataSource) {
        this.packaging = packaging;
        this.packages = packages;
        this.jdbc = JdbcClient.create(dataSource);
    }

    /**
     * The stored letter, or for a package built before the letter was stored, its file — which
     * only the template could have written, and whose time is the package's.
     *
     * @throws NoLetter when the offer has no package, or its folder holds no letter
     */
    public CoverLetter read(long offerId) {
        Stored stored = stored(offerId).orElseThrow(() -> new NoLetter(offerId));
        if (stored.text() != null) {
            return new CoverLetter(stored.text(), stored.author(), stored.at(), frozen(offerId));
        }
        Path file = folder(offerId).resolve(PackagingService.LETTER_FILE);
        if (!Files.isRegularFile(file)) {
            throw new NoLetter(offerId);
        }
        try {
            return new CoverLetter(
                    Files.readString(file, StandardCharsets.UTF_8),
                    PackagingService.Letter.TEMPLATE,
                    stored.packagedAt(),
                    frozen(offerId));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * A person's letter, byte for byte, as the package's letter from now on; its author is
     * {@code edited}, which no build drafts over.
     *
     * @throws NoLetter    when the offer has no package
     * @throws AlreadySent when the application stands at SENT or later
     */
    public CoverLetter save(long offerId, String text) {
        Path folder = folder(offerId);
        refuseIfSent(offerId);
        return store(offerId, folder, new PackagingService.Letter(text, PackagingService.Letter.EDITED));
    }

    /**
     * A fresh letter in place of the current one, whoever wrote it: the writing model's draft if
     * the guard accepts it, the template's otherwise, at most one budget permit. The person asked
     * for this, so it replaces an edited letter too.
     *
     * @throws NoLetter    when the offer has no package
     * @throws AlreadySent when the application stands at SENT or later, before the call or during it
     * @throws NoPermit    when the budget refused the call; nothing is written
     */
    public CoverLetter draft(long offerId) {
        Path folder = folder(offerId);
        refuseIfSent(offerId);
        PackagingService.Letter letter = packaging.redraft(offerId);
        // Again, because the model may have taken a minute and the application may have been
        // marked sent in it. Narrows the window to the few writes below; it does not close it.
        refuseIfSent(offerId);
        log.info("Offer {}: the cover letter was drafted again on request, by the {}", offerId, letter.author());
        return store(offerId, folder, letter);
    }

    private CoverLetter store(long offerId, Path folder, PackagingService.Letter letter) {
        try {
            Files.writeString(folder.resolve(PackagingService.LETTER_FILE), letter.text(), StandardCharsets.UTF_8);
            rewriteAuthor(folder.resolve("meta.json"), letter.author());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Instant at = jdbc.sql("""
                UPDATE offer
                SET cover_letter_text = ?, cover_letter_author = ?, cover_letter_at = now()
                WHERE id = ?
                RETURNING cover_letter_at
                """)
                .params(letter.text(), letter.author(), offerId)
                .query((rs, n) -> rs.getObject(1, OffsetDateTime.class).toInstant())
                .single();
        // Not frozen: both writes refused a sent application before they got here.
        return new CoverLetter(letter.text(), letter.author(), at, false);
    }

    /**
     * {@code meta.json}'s {@code cover_letter.author}, changed in place and nothing else with it.
     * A folder without one — the configuration builds no meta document — is left without one.
     */
    private void rewriteAuthor(Path meta, String author) throws IOException {
        if (!Files.isRegularFile(meta)) {
            return;
        }
        JsonNode tree = json.readTree(Files.readString(meta, StandardCharsets.UTF_8));
        if (!(tree instanceof ObjectNode object)) {
            return;
        }
        object.putObject("cover_letter").put("author", author);
        Files.writeString(
                meta, json.writerWithDefaultPrettyPrinter().writeValueAsString(object), StandardCharsets.UTF_8);
    }

    /** At SENT or past it; an offer without an application row has nothing that went out. */
    private boolean frozen(long offerId) {
        return jdbc.sql("SELECT status FROM application WHERE offer_id = ?")
                .param(offerId)
                .query(String.class)
                .optional()
                .map(status -> ApplicationStatus.valueOf(status).compareTo(ApplicationStatus.SENT) >= 0)
                .orElse(false);
    }

    private void refuseIfSent(long offerId) {
        if (frozen(offerId)) {
            throw new AlreadySent(offerId);
        }
    }

    /** The package folder, resolved the way the download resolves it, or {@link NoLetter}. */
    private Path folder(long offerId) {
        Optional<Path> folder;
        try {
            folder = packages.folderFor(offerId);
        } catch (PackageArchive.Rejected e) {
            throw new NoLetter(offerId);
        }
        return folder.filter(Files::isDirectory).orElseThrow(() -> new NoLetter(offerId));
    }

    private Optional<Stored> stored(long offerId) {
        return jdbc.sql("""
                SELECT cover_letter_text, cover_letter_author, cover_letter_at, packaged_at
                FROM offer WHERE id = ? AND package_dir IS NOT NULL
                """)
                .param(offerId)
                .query((rs, n) -> new Stored(
                        rs.getString("cover_letter_text"),
                        rs.getString("cover_letter_author"),
                        instant(rs.getObject("cover_letter_at", OffsetDateTime.class)),
                        instant(rs.getObject("packaged_at", OffsetDateTime.class))))
                .optional();
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    /** The letter columns of one packaged offer. */
    private record Stored(String text, String author, Instant at, Instant packagedAt) {}

    /** No package, so no letter. */
    public static class NoLetter extends RuntimeException {
        NoLetter(long offerId) {
            super("no package has been built for offer " + offerId + ", so it has no cover letter");
        }
    }

    /** The application stands at SENT or later, so the letter is the record of what went out. */
    public static class AlreadySent extends RuntimeException {
        AlreadySent(long offerId) {
            super("the application for offer " + offerId
                    + " stands at sent or later, so its cover letter can no longer be changed");
        }
    }

    /** No model call left in the budget; nothing was written. */
    public static class NoPermit extends RuntimeException {
        NoPermit(long offerId) {
            super("no model call is left in the call budget, so no cover letter was drafted for offer " + offerId);
        }
    }
}
