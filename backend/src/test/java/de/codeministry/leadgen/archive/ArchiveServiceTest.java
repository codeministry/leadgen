/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.archive;

import static org.assertj.core.api.Assertions.assertThat;

import de.codeministry.leadgen.config.ConfigFixtures;
import de.codeministry.leadgen.config.ConfigRegistry;
import java.time.LocalDate;
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
 * The age pass: what leaves the working list, what comes back, and what it must not touch.
 *
 * <p><b>Every date here is relative to the configured window, never to a number written in
 * this file.</b> `leadgen.config-dir` is searched upwards from the working directory, so on
 * a machine with an operator configuration these tests would otherwise assert against
 * whatever that person happens to have set — and fail for the one person who finished
 * configuring the tool. What is under test is the boundary, not its value.
 */
@SpringBootTest
@Testcontainers
class ArchiveServiceTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    /**
     * Pinned to the shipped defaults rather than to whatever `config/` this machine has.
     * Without it the thresholds, the rules and the profile come from the developer's own
     * directory through `.env`, and the build turns red for a value nobody committed.
     */
    @DynamicPropertySource
    static void configuration(DynamicPropertyRegistry registry) {
        registry.add(
                "leadgen.config-dir", () -> ConfigFixtures.shippedDefaults().toString());
    }

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 2);

    @Autowired
    private ArchiveService archive;

    @Autowired
    private ConfigRegistry config;

    @Autowired
    private JdbcTemplate jdbc;

    private long sourceId;
    private int window;

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM application_event");
        jdbc.update("DELETE FROM application");
        jdbc.update("DELETE FROM offer_score_reason");
        jdbc.update("DELETE FROM offer");
        jdbc.update("DELETE FROM source");
        sourceId =
                jdbc.queryForObject("INSERT INTO source (name, kind) VALUES ('test', 'file') RETURNING id", Long.class);
        window = config.snapshot().rules().hardFilters().freshness().maxAgeDays();
    }

    @Test
    void archivesWhatIsPastTheWindowAndKeepsWhatIsExactlyOnIt() {
        // Strictly before the cutoff, which is the boundary the filter's freshness rule
        // used. Moving the rule to another class must not move it by a day.
        long old = offer("Zu alt", TODAY.minusDays(window + 1));
        long edge = offer("Genau auf der Grenze", TODAY.minusDays(window));

        var report = archive.run(TODAY);

        assertThat(archivedAt(old)).isNotNull();
        assertThat(archivedAt(edge)).isNull();
        assertThat(report.archived()).isEqualTo(1);
        assertThat(report.standing()).isEqualTo(1);
    }

    @Test
    void leavesAnOfferWithNoDateAlone() {
        // No date, no age. It stays on the list forever, which is a number in the report
        // rather than a silence: it is the one way an offer can never leave.
        long undated = offer("Ohne Datum", null);

        var report = archive.run(TODAY);

        assertThat(archivedAt(undated)).isNull();
        assertThat(report.undated()).isEqualTo(1);
    }

    @Test
    void neverArchivesAnOfferSomebodyIsWorkingOn() {
        // The board is the only place this state exists. Archiving the offer away would
        // make the one screen that reads it show a card pointing at nothing.
        long working = offer("Beworben", TODAY.minusDays(window + 30));
        application(working, "SENT");

        archive.run(TODAY);

        assertThat(archivedAt(working)).isNull();
    }

    @Test
    void archivesAnOfferWhoseApplicationIsOnlyTheOneThePackagerOpened() {
        // `PackagingService` opens an application the moment it builds a folder, so
        // treating PACKAGED as "in progress" would exempt every offer that ever reached
        // the shortlist — which is the whole shortlist.
        long packaged = offer("Nur verpackt", TODAY.minusDays(window + 30));
        application(packaged, "PACKAGED");

        archive.run(TODAY);

        assertThat(archivedAt(packaged)).isNotNull();
    }

    @Test
    void archivesAnOfferWhoseApplicationIsClosed() {
        long lost = offer("Verloren", TODAY.minusDays(window + 30));
        application(lost, "LOST");

        archive.run(TODAY);

        assertThat(archivedAt(lost)).isNotNull();
    }

    @Test
    void bringsBackWhatIsInsideTheWindowAgain() {
        // While the rule lived in the filter, staleness was recomputed every run, so
        // widening the window brought offers back. A written state that quietly stopped
        // doing that would be a behaviour change nobody asked for.
        long aged = offer("Wieder drin", TODAY.minusDays(window + 1));
        archive.run(TODAY);
        assertThat(archivedAt(aged)).isNotNull();

        // The same offer, judged on a day when it is inside the window again — which is
        // what widening `max_age_days` amounts to.
        var report = archive.run(TODAY.minusDays(2));

        assertThat(archivedAt(aged)).isNull();
        assertThat(report.restored()).isEqualTo(1);
    }

    @Test
    void neverBringsBackWhatAPersonArchived() {
        long byHand = offer("Von Hand raus", TODAY);
        jdbc.update("UPDATE offer SET archived_at = now(), archive_source = 'MANUAL' WHERE id = ?", byHand);

        var report = archive.run(TODAY);

        assertThat(archivedAt(byHand)).isNotNull();
        assertThat(report.restored()).isZero();
    }

    @Test
    void neverArchivesAgainWhatAPersonTookBack() {
        // A restore the next run undoes is a button that lies, and this is the whole
        // reason `archive_source` exists beside `archived_at`.
        long restored = offer("Zurückgeholt", TODAY.minusDays(window + 100));
        jdbc.update("UPDATE offer SET archive_source = 'RESTORED' WHERE id = ?", restored);

        archive.run(TODAY);

        assertThat(archivedAt(restored)).isNull();
    }

    @Test
    void movesNothingOnASecondRun() {
        offer("Zu alt", TODAY.minusDays(window + 1));
        offer("Frisch", TODAY);

        archive.run(TODAY);
        var second = archive.run(TODAY);

        assertThat(second.archived()).isZero();
        assertThat(second.restored()).isZero();
        assertThat(second.standing()).isEqualTo(1);
    }

    private long offer(String title, LocalDate publishedOn) {
        return jdbc.queryForObject(
                """
                INSERT INTO offer (source_id, external_id, title, url, fingerprint, status, published_on)
                VALUES (?, ?, ?, 'https://example.invalid/x', ?, 'PASSED', ?)
                RETURNING id
                """,
                Long.class,
                sourceId,
                title,
                title,
                title.toLowerCase(),
                publishedOn);
    }

    private void application(long offerId, String status) {
        jdbc.update("INSERT INTO application (offer_id, status) VALUES (?, ?)", offerId, status);
    }

    private Object archivedAt(long id) {
        return jdbc.queryForObject("SELECT archived_at FROM offer WHERE id = ?", Object.class, id);
    }
}
