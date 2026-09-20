/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.application;

import de.codeministry.leadgen.Databases;
import de.codeministry.leadgen.config.ConfigFixtures;
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

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The half of the loop the system cannot observe: what happened after a package was
 * built, entered by hand.
 */
@SpringBootTest
@Testcontainers
class ApplicationServiceTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = Databases.postgres();

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

    @Autowired
    private ApplicationService applications;

    @Autowired
    private JdbcTemplate jdbc;

    private long offerId;

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM application_event");
        jdbc.update("DELETE FROM application");
        jdbc.update("DELETE FROM offer");
        jdbc.update("DELETE FROM source");
        long sourceId =
                jdbc.queryForObject("INSERT INTO source (name, kind) VALUES ('test', 'file') RETURNING id", Long.class);
        offerId = jdbc.queryForObject("""
            INSERT INTO offer (source_id, external_id, title, url, fingerprint, status,
                               score_value, score_band, agency, portal)
            VALUES (?, 'ext-1', 'Senior Java Entwickler (m/w/d)', 'https://example.invalid/x', 'fp',
                    'PASSED', 88, 'SHORTLISTED', 'Acme Consulting GmbH', 'portal-a')
            RETURNING id
            """, Long.class, sourceId);
    }

    @Test
    void opensOnceAndNeverResetsWhatTheOperatorMoved() {
        // A second packaging run must not undo a status someone has already advanced.
        long id = applications.open(offerId, ApplicationStatus.PACKAGED);
        applications.update(id, update(ApplicationStatus.SENT));

        assertThat(applications.open(offerId, ApplicationStatus.PACKAGED)).isEqualTo(id);
        assertThat(applications.find(id).orElseThrow().status()).isEqualTo(ApplicationStatus.SENT);
    }

    @Test
    void acceptsAnyTransitionBecauseTheOperatorIsTheAuthority() {
        // A project can be lost before it was ever answered, and a mistyped status has to
        // be correctable without an argument. The states describe the usual path, not a
        // rule the tool enforces against the person who was actually there.
        long id = applications.open(offerId, ApplicationStatus.PACKAGED);

        applications.update(id, update(ApplicationStatus.LOST));
        assertThat(applications.find(id).orElseThrow().status()).isEqualTo(ApplicationStatus.LOST);

        applications.update(id, update(ApplicationStatus.SENT));
        assertThat(applications.find(id).orElseThrow().status()).isEqualTo(ApplicationStatus.SENT);
    }

    @Test
    void datesASentApplicationRatherThanRefusingIt() {
        // "Sent, at some point" is not a fact anyone can act on, and refusing the update
        // would cost the status too. Today is right far more often than it is wrong.
        long id = applications.open(offerId, ApplicationStatus.PACKAGED);

        applications.update(id, update(ApplicationStatus.SENT));

        assertThat(applications.find(id).orElseThrow().sentOn()).isEqualTo(LocalDate.now());
    }

    @Test
    void keepsAnExplicitSendDate() {
        long id = applications.open(offerId, ApplicationStatus.PACKAGED);
        var yesterday = LocalDate.now().minusDays(1);

        applications.update(id, new ApplicationUpdate(ApplicationStatus.SENT, yesterday, null, null, null, null));

        assertThat(applications.find(id).orElseThrow().sentOn()).isEqualTo(yesterday);
    }

    @Test
    void marksAFollowUpDueOnceItsDayHasCome() {
        long id = applications.open(offerId, ApplicationStatus.PACKAGED);

        applications.update(
                id,
                new ApplicationUpdate(
                        ApplicationStatus.SENT, null, LocalDate.now().minusDays(1), null, null, null));
        assertThat(applications.find(id).orElseThrow().followUpDue()).isTrue();
        assertThat(applications.followUpsDue()).isEqualTo(1);

        applications.update(
                id,
                new ApplicationUpdate(
                        ApplicationStatus.SENT, null, LocalDate.now().plusDays(3), null, null, null));
        assertThat(applications.find(id).orElseThrow().followUpDue()).isFalse();
    }

    @Test
    void dropsTheFollowUpWhenTheApplicationCloses() {
        // A lost project with a standing reminder is how a follow-up list stops being read.
        long id = applications.open(offerId, ApplicationStatus.PACKAGED);
        applications.update(
                id,
                new ApplicationUpdate(
                        ApplicationStatus.SENT, null, LocalDate.now().minusDays(1), null, null, null));

        applications.update(id, update(ApplicationStatus.LOST));

        assertThat(applications.find(id).orElseThrow().followUpOn()).isNull();
        assertThat(applications.followUpsDue()).isZero();
    }

    @Test
    void cancelsAFollowUpOnRequest() {
        // A null date cannot say both "leave it alone" and "remove it", and a board where
        // a reminder can be set but never cancelled fills up with dead reminders.
        long id = applications.open(offerId, ApplicationStatus.PACKAGED);
        applications.update(
                id,
                new ApplicationUpdate(
                        ApplicationStatus.SENT, null, LocalDate.now().plusDays(3), null, null, null));

        applications.update(id, new ApplicationUpdate(ApplicationStatus.SENT, null, null, true, null, null));

        assertThat(applications.find(id).orElseThrow().followUpOn()).isNull();
    }

    @Test
    void leavesAFollowUpAloneWhenTheUpdateSaysNothingAboutIt() {
        long id = applications.open(offerId, ApplicationStatus.PACKAGED);
        var due = LocalDate.now().plusDays(3);
        applications.update(id, new ApplicationUpdate(ApplicationStatus.SENT, null, due, null, null, null));

        applications.update(id, update(ApplicationStatus.REPLIED));

        assertThat(applications.find(id).orElseThrow().followUpOn()).isEqualTo(due);
    }

    @Test
    void keepsEveryChangeAsHistory() {
        // A single mutable row cannot answer "when did I send this" after the second
        // correction.
        long id = applications.open(offerId, ApplicationStatus.PACKAGED);
        applications.update(id, update(ApplicationStatus.SENT));
        applications.update(id, update(ApplicationStatus.REPLIED));

        var history = applications.history(id);

        assertThat(history).hasSize(3);
        assertThat(history.getFirst().toStatus()).isEqualTo(ApplicationStatus.REPLIED);
        assertThat(history.getFirst().fromStatus()).isEqualTo(ApplicationStatus.SENT);
        assertThat(history.getLast().fromStatus()).isNull();
    }

    @Test
    void recordsNoEventWhenOnlyADateChanged() {
        long id = applications.open(offerId, ApplicationStatus.PACKAGED);
        applications.update(id, update(ApplicationStatus.SENT));
        int after = applications.history(id).size();

        applications.update(
                id,
                new ApplicationUpdate(ApplicationStatus.SENT, LocalDate.now().minusDays(2), null, null, null, null));

        assertThat(applications.history(id)).hasSize(after);
    }

    @Test
    void carriesEnoughOfTheOfferToRecogniseTheCard() {
        long id = applications.open(offerId, ApplicationStatus.PACKAGED);

        var view = applications.find(id).orElseThrow();

        assertThat(view.title()).isEqualTo("Senior Java Entwickler (m/w/d)");
        assertThat(view.agency()).isEqualTo("Acme Consulting GmbH");
        assertThat(view.scoreValue()).isEqualTo(88);
    }

    @Test
    void leavesAnArchivedOfferOffTheBoardButStillCorrectable() {
        long id = applications.open(offerId, ApplicationStatus.SENT);
        assertThat(applications.board()).extracting(ApplicationView::id).contains(id);

        jdbc.update("UPDATE offer SET archived_at = now(), archive_source = 'MANUAL' WHERE id = ?", offerId);

        // Off the working list, and off the dashboard's follow-up count with it.
        assertThat(applications.board()).isEmpty();

        // Still correctable: the offer detail is reachable for any offer, and an archived
        // application whose status could no longer be moved would be a dead end.
        assertThat(applications.find(id)).isPresent();
        assertThat(applications.update(id, update(ApplicationStatus.LOST)).status())
            .isEqualTo(ApplicationStatus.LOST);
    }

    @Test
    void namesTheIdItCannotFind() {
        assertThatThrownBy(() -> applications.update(999_999L, update(ApplicationStatus.SENT)))
                .isInstanceOf(ApplicationService.ApplicationNotFound.class)
                .hasMessageContaining("999999");
    }

    @Test
    void refusesToStepOverThePackage() {
        // The one enforced transition, and the reason is not tidiness: the folder an
        // application is sent from is built on the way into PACKAGED, so a route around it
        // would produce a SENT application with nothing on disk behind it.
        long id = applications.open(offerId, ApplicationStatus.NEW);

        assertThatThrownBy(() -> applications.update(id, update(ApplicationStatus.SENT)))
            .isInstanceOf(ApplicationService.TransitionRefused.class)
            .hasMessageContaining("PACKAGED");

        // And nothing happened: not the status, and not the event that would claim it did.
        assertThat(applications.find(id).orElseThrow().status()).isEqualTo(ApplicationStatus.NEW);
        assertThat(applications.history(id)).hasSize(1);
    }

    @Test
    void refusesAMoveThatWouldHaveDatedASendThatNeverHappened() {
        // The guard sits in front of the `isOut()` defaulting for this reason. Behind it,
        // a refused request would already have computed today as the send date.
        long id = applications.open(offerId, ApplicationStatus.NEW);

        assertThatThrownBy(() -> applications.update(id, update(ApplicationStatus.INTERVIEW)))
            .isInstanceOf(ApplicationService.TransitionRefused.class);

        assertThat(applications.find(id).orElseThrow().sentOn()).isNull();
    }

    @Test
    void letsAnUndecidedOfferBePreparedOrDecidedAgainst() {
        // Deciding against an offer must never need a package first. Marking it as one to
        // come back to must not either, or SHORTLISTED is a state nothing can reach.
        for (ApplicationStatus allowed : java.util.List.of(
            ApplicationStatus.SHORTLISTED, ApplicationStatus.REJECTED, ApplicationStatus.EXPIRED)) {
            reset();
            long id = applications.open(offerId, ApplicationStatus.NEW);
            assertThat(applications.update(id, update(allowed)).status()).isEqualTo(allowed);
        }
    }

    @Test
    void leavesEverythingAfterThePackageFree() {
        // Past the folder the old rule stands unchanged: a project can be lost before it
        // was ever answered, and a mistyped status has to be correctable in any direction.
        long id = applications.open(offerId, ApplicationStatus.PACKAGED);

        assertThat(applications.update(id, update(ApplicationStatus.LOST)).status())
            .isEqualTo(ApplicationStatus.LOST);
        assertThat(applications.update(id, update(ApplicationStatus.NEW)).status())
            .isEqualTo(ApplicationStatus.NEW);
    }

    @Test
    void putsEveryShortlistedOfferOnTheBoardAtNewAndOnlyOnce() {
        var first = applications.openShortlisted();

        assertThat(first.opened()).isEqualTo(1);
        assertThat(first.standing()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM application WHERE offer_id = ?", String.class, offerId))
            .isEqualTo("NEW");
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM application_event WHERE to_status = 'NEW'", Integer.class))
            .isEqualTo(1);

        // A second run in the same morning opens nothing, and resets nothing either.
        // Moved to SHORTLISTED rather than to PACKAGED on purpose: the latter publishes a
        // request for a folder, and this test has no business writing one to disk.
        applications.update(
            jdbc.queryForObject("SELECT id FROM application WHERE offer_id = ?", Long.class, offerId),
            update(ApplicationStatus.SHORTLISTED));

        assertThat(applications.openShortlisted().opened()).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM application WHERE offer_id = ?", String.class, offerId))
            .isEqualTo("SHORTLISTED");
    }

    @Test
    void leavesWhatTheShortlistDidNotRecommendOffTheBoard() {
        jdbc.update("UPDATE offer SET score_band = 'REVIEW' WHERE id = ?", offerId);

        assertThat(applications.openShortlisted().opened()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM application", Integer.class))
            .isZero();
    }

    @Test
    void leavesAnArchivedOfferOffTheBoard() {
        jdbc.update("UPDATE offer SET archived_at = now(), archive_source = 'MANUAL' WHERE id = ?", offerId);

        assertThat(applications.openShortlisted().opened()).isZero();
    }

    private static ApplicationUpdate update(ApplicationStatus status) {
        return new ApplicationUpdate(status, null, null, null, null, null);
    }
}
