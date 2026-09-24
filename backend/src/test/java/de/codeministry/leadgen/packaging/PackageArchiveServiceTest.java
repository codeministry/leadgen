/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.packaging;

import static org.assertj.core.api.Assertions.assertThat;

import de.codeministry.leadgen.Databases;
import de.codeministry.leadgen.archive.ArchiveService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * What archiving an offer does to its package.
 *
 * <p>A folder costs disk for as long as it exists and is rebuildable from the same advert,
 * so an offer leaving the working list takes its package with it. The exception is the one
 * thing that is not rebuildable: what was actually sent. That folder is the record of it.
 */
@SpringBootTest
@Testcontainers
class PackageArchiveServiceTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = Databases.postgres();

    @DynamicPropertySource
    static void configuration(DynamicPropertyRegistry registry) {
        registry.add("leadgen.config-dir", () -> PackagesFixture.config().toString());
    }

    @Autowired
    private PackageArchiveService packages;

    @Autowired
    private ArchiveService archive;

    @Autowired
    private JdbcTemplate jdbc;

    private long sourceId;

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM application");
        jdbc.update("DELETE FROM offer");
        jdbc.update("DELETE FROM source");
        sourceId =
                jdbc.queryForObject("INSERT INTO source (name, kind) VALUES ('test', 'file') RETURNING id", Long.class);
    }

    @Test
    void throwsAwayAPackageThatWasNeverSent() {
        Path folder = PackagesFixture.aPackage("2026-09-02_acme_nie-verschickt");
        long id = packaged("Nie verschickt", folder, "PACKAGED");

        assertThat(packages.discard(List.of(id))).isEqualTo(1);

        assertThat(Files.exists(folder)).isFalse();
        // And the reference with it. Nulling `packaged_at` is what lets a later move back to
        // PACKAGED build the folder again; leaving `package_dir` would keep a download link
        // that answers 404.
        assertThat(jdbc.queryForObject(
                        "SELECT package_dir IS NULL AND packaged_at IS NULL AND language IS NULL"
                                + " FROM offer WHERE id = ?",
                        Boolean.class,
                        id))
                .isTrue();
    }

    @Test
    void keepsThePackageOfAnApplicationThatWentOut() {
        // Deliberately without an event: deleting is the irreversible half of this, so a row
        // standing at SENT with a hole in its log must not lose the folder over it.
        Path folder = PackagesFixture.aPackage("2026-09-02_acme_verschickt");
        long id = packaged("Verschickt", folder, "SENT");

        assertThat(packages.discard(List.of(id))).isZero();

        assertThat(Files.exists(folder)).isTrue();
        assertThat(jdbc.queryForObject("SELECT package_dir FROM offer WHERE id = ?", String.class, id))
                .isNotNull();
    }

    @Test
    void asksTheEventLogRatherThanTheCurrentStatus() {
        // The reason the check is an event and not a status: a LOST application may have
        // been answered and lost, or written off before anybody wrote a line, and those two
        // have opposite answers here.
        Path answered = PackagesFixture.aPackage("2026-09-02_acme_beantwortet-und-verloren");
        long lostAfterSending = packaged("Beantwortet und verloren", answered, "LOST");
        event(lostAfterSending, "PACKAGED", "SENT");
        event(lostAfterSending, "SENT", "LOST");

        Path neverAnswered = PackagesFixture.aPackage("2026-09-02_acme_nie-beantwortet");
        long lostWithoutSending = packaged("Nie beantwortet", neverAnswered, "LOST");
        event(lostWithoutSending, "PACKAGED", "LOST");

        assertThat(packages.discard(List.of(lostAfterSending, lostWithoutSending)))
                .isEqualTo(1);

        assertThat(Files.exists(answered)).isTrue();
        assertThat(Files.exists(neverAnswered)).isFalse();
    }

    @Test
    void cleansUpTheRowOfAFolderThatIsAlreadyGone() throws IOException {
        // A folder removed by hand, or by a discard whose row write never landed. The
        // reference has to go either way, which is what makes this safe to run twice.
        Path folder = PackagesFixture.aPackage("2026-09-02_acme_schon-weg");
        long id = packaged("Schon weg", folder, "PACKAGED");
        PackageArchive.deleteFolder(folder);

        assertThat(packages.discard(List.of(id))).isEqualTo(1);

        assertThat(jdbc.queryForObject("SELECT package_dir FROM offer WHERE id = ?", String.class, id))
                .isNull();
    }

    /**
     * ISC-262: the stored letter is part of the package. An unsent one goes with the folder,
     * and the restore that brings the offer back at NEW brings no letter with it.
     */
    @Test
    void throwsAwayTheStoredLetterWithAnUnsentPackageAndRestoresNone() {
        Path folder = PackagesFixture.aPackage("2026-09-02_acme_brief-weg");
        long id = packaged("Brief weg", folder, "PACKAGED");
        letter(id);
        jdbc.update("UPDATE offer SET archived_at = now(), archive_source = 'MANUAL' WHERE id = ?", id);

        assertThat(packages.discard(List.of(id))).isEqualTo(1);
        assertThat(letterIsGone(id)).isTrue();

        assertThat(archive.setArchived(id, false)).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM application WHERE offer_id = ?", String.class, id))
                .isEqualTo("NEW");
        assertThat(letterIsGone(id)).isTrue();
    }

    /** ISC-262: what went out keeps its letter, as it keeps its folder. */
    @Test
    void keepsTheStoredLetterOfAnApplicationThatWentOut() {
        Path folder = PackagesFixture.aPackage("2026-09-02_acme_brief-bleibt");
        long id = packaged("Brief bleibt", folder, "SENT");
        letter(id);

        assertThat(packages.discard(List.of(id))).isZero();

        assertThat(jdbc.queryForObject(
                        "SELECT cover_letter_text = 'Sehr geehrte Damen und Herren' AND cover_letter_author = 'edited'"
                                + " AND cover_letter_at IS NOT NULL FROM offer WHERE id = ?",
                        Boolean.class,
                        id))
                .isTrue();
    }

    private void letter(long offerId) {
        jdbc.update(
                "UPDATE offer SET cover_letter_text = 'Sehr geehrte Damen und Herren', cover_letter_author = 'edited',"
                        + " cover_letter_at = now() WHERE id = ?",
                offerId);
    }

    private Boolean letterIsGone(long offerId) {
        return jdbc.queryForObject(
                "SELECT cover_letter_text IS NULL AND cover_letter_author IS NULL AND cover_letter_at IS NULL"
                        + " FROM offer WHERE id = ?",
                Boolean.class,
                offerId);
    }

    @Test
    void doesNothingForAnOfferThatNeverHadAPackage() {
        long id = packaged("Ohne Paket", null, "NEW");

        assertThat(packages.discard(List.of(id))).isZero();
    }

    private long packaged(String title, Path folder, String status) {
        long id = jdbc.queryForObject(
                """
                INSERT INTO offer (source_id, external_id, title, url, fingerprint, status, package_dir,
                                   packaged_at, language)
                VALUES (?, ?, ?, 'https://example.invalid/x', ?, 'PASSED', ?, now(), 'de')
                RETURNING id
                """,
                Long.class,
                sourceId,
                title,
                title,
                title.toLowerCase(),
                folder == null ? null : folder.toString());
        jdbc.update("INSERT INTO application (offer_id, status) VALUES (?, ?)", id, status);
        return id;
    }

    private void event(long offerId, String from, String to) {
        jdbc.update("""
                INSERT INTO application_event (application_id, from_status, to_status)
                SELECT id, ?, ? FROM application WHERE offer_id = ?
                """, from, to, offerId);
    }
}
