/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import de.codeministry.leadgen.application.ApplicationStatus;
import de.codeministry.leadgen.config.ConfigFixtures;
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
 * What the analytics screen reads.
 *
 * <p>Every case here is a number that would be wrong in a way nobody would notice: a gap
 * that reads as a quiet market, a duplicate counted as a project, a perfect score falling
 * out of the histogram, a median computed over an unanswered application.
 */
@SpringBootTest
@Testcontainers
class AnalyticsQueryServiceTest {

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

    @Autowired
    private AnalyticsQueryService analytics;

    @Autowired
    private JdbcTemplate jdbc;

    private long sourceId;

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM application_event");
        jdbc.update("DELETE FROM application");
        jdbc.update("DELETE FROM offer_score_reason");
        jdbc.update("DELETE FROM offer");
        jdbc.update("DELETE FROM pipeline_run_stage");
        jdbc.update("DELETE FROM pipeline_run");
        jdbc.update("DELETE FROM source_run");
        jdbc.update("DELETE FROM source");
        sourceId =
                jdbc.queryForObject("INSERT INTO source (name, kind) VALUES ('test', 'file') RETURNING id", Long.class);
    }

    @Test
    void fillsTheDaysNothingArrivedOn() {
        // A line drawn straight across a gap claims a quiet market on a day when nothing
        // ran. The empty days have to be in the series, not left to the chart to invent.
        arrived(LocalDate.now().minusDays(4));
        arrived(LocalDate.now());

        var days = analytics.analytics().intake().byIngestedAt();

        assertThat(days).hasSize(5);
        assertThat(days).extracting(IntakeSeries.Day::primaries).containsExactly(1, 0, 0, 0, 1);
    }

    @Test
    void countsADuplicateAsADuplicateAndNotAsAProject() {
        // 12.3 % of the corpus arrives twice. Counted as projects they would make the market
        // look busier than it is, and left out entirely the collapse would be invisible.
        LocalDate today = LocalDate.now();
        long primary = arrived(today);
        long second = arrived(today);
        jdbc.update("UPDATE offer SET duplicate_of_id = ? WHERE id = ?", primary, second);

        var day = analytics.analytics().intake().byIngestedAt().getLast();

        assertThat(day.primaries()).isEqualTo(1);
        assertThat(day.duplicates()).isEqualTo(1);
    }

    @Test
    void keepsAnOfferOlderThanTheWindowOutOfTheAxisAndSaysHowMany() {
        // Two reasons for the clamp, and this covers both. A misparsed date in 1970 would
        // stretch the axis over twenty thousand empty days; a genuinely old advert would
        // stretch it over months of empty weeks. Neither is dropped in silence.
        published(LocalDate.now().minusDays(2));
        published(LocalDate.now());
        published(LocalDate.of(1970, 1, 1));
        // Genuinely published, genuinely too old for a ninety-day window.
        published(LocalDate.now().minusDays(120));
        arrivedWithoutPublicationDate();

        var intake = analytics.analytics().intake();

        // Three days spanned and filled, and the 1970 row neither in the span nor lost.
        assertThat(intake.byPublishedOn()).hasSize(3);
        assertThat(intake.byPublishedOn())
                .extracting(IntakeSeries.Day::primaries)
                .containsExactly(1, 0, 1);
        assertThat(intake.publishedOutOfRange()).isEqualTo(2);
        assertThat(intake.withoutPublishedOn()).isEqualTo(1);
    }

    @Test
    void putsAPerfectScoreInTheTopBucketRatherThanLosingIt() {
        // width_bucket(100, 0, 100, 10) is 11, which is a bucket nothing draws.
        scored(100);
        scored(95);
        scored(0);

        var buckets = analytics.analytics().scores().buckets();

        assertThat(buckets).hasSize(10);
        assertThat(buckets.getFirst().floor()).isZero();
        assertThat(buckets.getFirst().count()).isEqualTo(1);
        assertThat(buckets.getLast().floor()).isEqualTo(90);
        assertThat(buckets.getLast().count()).isEqualTo(2);
    }

    @Test
    void countsUnscoredFromTheBandAndNotFromAMissingNumber() {
        // The scorer writes the literal 'UNSCORED' when there is no judge. A null band means
        // the offer never reached the scorer, which is a different thing.
        long unscored = arrived(LocalDate.now());
        jdbc.update("UPDATE offer SET score_band = 'UNSCORED' WHERE id = ?", unscored);
        arrived(LocalDate.now());

        assertThat(analytics.analytics().scores().unscored()).isEqualTo(1);
    }

    @Test
    void readsTheThresholdsFromTheConfigurationRatherThanRestatingThem() {
        var scores = analytics.analytics().scores();

        assertThat(scores.shortlistAt()).isGreaterThan(scores.reviewAt());
        assertThat(scores.bucketSize()).isEqualTo(10);
    }

    @Test
    void showsAPortalThatOnlyEverCarriedDuplicates() {
        // Counting primaries only would show zero listings for a portal that publishes every
        // day, because another portal happened to get there first every time.
        long primary = arrived(LocalDate.now(), "portal-a");
        long second = arrived(LocalDate.now(), "portal-c");
        jdbc.update("UPDATE offer SET duplicate_of_id = ? WHERE id = ?", primary, second);

        var portals = analytics.analytics().market().portals();

        assertThat(portals).extracting(MarketView.Portal::portal).contains("portal-c");
        var duplicateOnly = portals.stream()
                .filter(portal -> "portal-c".equals(portal.portal()))
                .findFirst()
                .orElseThrow();
        assertThat(duplicateOnly.listings()).isEqualTo(1);
        assertThat(duplicateOnly.projects()).isZero();
    }

    @Test
    void readsTheSearchTagsOfTheProjectsAndNotOfEveryListing() {
        long primary = arrived(LocalDate.now());
        long second = arrived(LocalDate.now());
        jdbc.update("UPDATE offer SET tags = ARRAY['Java', 'Cloud'] WHERE id = ?", primary);
        jdbc.update("UPDATE offer SET tags = ARRAY['Java'], duplicate_of_id = ? WHERE id = ?", primary, second);

        assertThat(analytics.analytics().market().tags())
                .extracting(MarketView.Tag::tag, MarketView.Tag::projects)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("Java", 1),
                        org.assertj.core.groups.Tuple.tuple("Cloud", 1));
    }

    @Test
    void leavesTheMedianEmptyWhenNothingHasBeenAnswered() {
        // Null rather than zero: "no answer yet" and "answered the same day" are opposites,
        // and a zero there would read as an instant reply.
        long offer = arrived(LocalDate.now());
        long application = sent(offer, LocalDate.now().minusDays(3));
        moved(application, ApplicationStatus.SENT);

        var response = analytics.analytics().applications().response();

        assertThat(response.sent()).isEqualTo(1);
        assertThat(response.answered()).isZero();
        assertThat(response.medianDaysToFirstReply()).isNull();
    }

    @Test
    void countsARejectionAsAnAnswerAndMeasuresHowLongItTook() {
        // A no is a reply. LOST and EXPIRED are not: a project can end with nobody ever
        // having written back, and counting those would make the response rate flatter.
        long quick = sent(arrived(LocalDate.now()), LocalDate.now().minusDays(4));
        moved(quick, ApplicationStatus.SENT);
        movedOn(quick, ApplicationStatus.REJECTED, LocalDate.now().minusDays(2));

        long slow = sent(arrived(LocalDate.now()), LocalDate.now().minusDays(10));
        moved(slow, ApplicationStatus.SENT);
        movedOn(slow, ApplicationStatus.INTERVIEW, LocalDate.now().minusDays(4));

        long silent = sent(arrived(LocalDate.now()), LocalDate.now().minusDays(9));
        moved(silent, ApplicationStatus.SENT);
        movedOn(silent, ApplicationStatus.EXPIRED, LocalDate.now().minusDays(1));

        var response = analytics.analytics().applications().response();

        assertThat(response.sent()).isEqualTo(3);
        assertThat(response.answered()).isEqualTo(2);
        // Two answers, at two and at six days.
        assertThat(response.medianDaysToFirstReply()).isCloseTo(4.0, within(0.001));
    }

    @Test
    void keepsABackdatedSendOutOfTheMedianAndCountsIt() {
        // A reply recorded before the send date is a data-entry fact, not a response time.
        // Clamped to zero it would quietly pull the median down.
        long backdated = sent(arrived(LocalDate.now()), LocalDate.now());
        moved(backdated, ApplicationStatus.SENT);
        movedOn(backdated, ApplicationStatus.REPLIED, LocalDate.now().minusDays(3));

        var response = analytics.analytics().applications().response();

        assertThat(response.backdated()).isEqualTo(1);
        assertThat(response.answered()).isZero();
        assertThat(response.medianDaysToFirstReply()).isNull();
    }

    @Test
    void listsEveryApplicationStateIncludingTheEmptyOnes() {
        // A state nothing is in is a fact about the board, the same way a filter stage that
        // removed nothing is a fact about the filter.
        assertThat(analytics.analytics().applications().byStatus())
                .hasSize(ApplicationStatus.values().length)
                .allSatisfy(count -> assertThat(count.applications()).isZero());
    }

    @Test
    void answersOnAnEmptyArchiveWithoutFailing() {
        // A fresh clone on its first morning opens this screen before anything has run.
        var view = analytics.analytics();

        assertThat(view.intake().byIngestedAt()).isEmpty();
        assertThat(view.runs().days()).isEmpty();
        assertThat(view.scales()).isEmpty();
        assertThat(view.from()).isNull();
        assertThat(view.scores().buckets()).hasSize(10);
    }

    @Test
    void namesEveryScaleTheArchivesScoresWereProducedUnder() {
        // One row means the caveat about today's rules is currently harmless; two mean the
        // archive already mixes two scales and no score comparison across time holds.
        long first = scored(80);
        long second = scored(60);
        jdbc.update("UPDATE offer SET ruleset_version = '1', score_model = 'a' WHERE id = ?", first);
        jdbc.update("UPDATE offer SET ruleset_version = '2', score_model = 'b' WHERE id = ?", second);

        assertThat(analytics.analytics().scales())
                .extracting(ScaleInUse::rulesetVersion, ScaleInUse::scoreModel)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("1", "a"), org.assertj.core.groups.Tuple.tuple("2", "b"));
    }

    @Test
    void carriesTheRunsThemselvesOldestFirstAndSaysSinceWhenItHasThem() {
        // The one series that is neither the current archive nor a recomputation of it: it
        // is what each run said it had done, before the next run overwrote the evidence.
        pass("2026-09-01T10:00:00Z", 100, 40);
        pass("2026-09-02T10:00:00Z", 120, 50);

        var runs = analytics.analytics().runs();

        assertThat(runs.passes()).extracting(RunSeries.Pass::extracted).containsExactly(100, 120);
        assertThat(runs.historySince()).isNotNull();
    }

    @Test
    void saysNothingRatherThanZeroWhenNoRunWasEverRecorded() {
        // A chart that starts three weeks ago has to say so, not imply that nothing
        // happened before. Null is how it says it.
        var runs = analytics.analytics().runs();

        assertThat(runs.passes()).isEmpty();
        assertThat(runs.historySince()).isNull();
    }

    private void pass(String finishedAt, int extracted, int passed) {
        jdbc.update(
                """
                INSERT INTO pipeline_run (
                    started_at, finished_at, ruleset_version, score_model, status,
                    documents, extracted, written, merged,
                    filter_considered, filter_passed,
                    enrich_considered, enriched, incomplete, from_cache, requests,
                    score_considered, scored, unscored, shortlisted, review, submitted,
                    packaged, digest_written)
                VALUES (?, ?, '1', 'a-model', 'COMPLETE', 1, ?, ?, 0, ?, ?, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, true)
                """,
                java.sql.Timestamp.from(java.time.Instant.parse(finishedAt)),
                java.sql.Timestamp.from(java.time.Instant.parse(finishedAt)),
                extracted,
                extracted,
                extracted,
                passed);
    }

    @Test
    void readsTheMailsOwnDateSeparatelyFromTheRunsAndSaysWhatHasNone() {
        // The three dates answer three questions, and only this one measures the market:
        // the ingest axis moves for every row when the database is refilled.
        long byMail = arrived(LocalDate.now());
        jdbc.update(
                "UPDATE offer SET received_at = ? WHERE id = ?",
                java.sql.Timestamp.valueOf(LocalDate.now().minusDays(3).atTime(8, 0)),
                byMail);
        arrived(LocalDate.now());

        var intake = analytics.analytics().intake();

        assertThat(intake.byReceivedAt()).hasSize(1);
        assertThat(intake.byReceivedAt().getFirst().primaries()).isEqualTo(1);
        // The one with no mail behind it. A file dropped in by hand has no arrival date,
        // and inventing one from its mtime would be the run's date in disguise.
        assertThat(intake.withoutReceivedAt()).isEqualTo(1);
    }

    @Test
    void sendsTheStageMixByDaySoTheBrowserBucketsItOnce() {
        // Aggregated weekly in SQL, this chart showed one bar while the intake chart above
        // it showed two days of the same archive. One rule, applied in one place.
        long rejected = arrived(LocalDate.now());
        jdbc.update("UPDATE offer SET filter_stage = 'ABROAD', status = 'FILTERED_OUT' WHERE id = ?", rejected);
        long older = arrived(LocalDate.now().minusDays(3));
        jdbc.update("UPDATE offer SET filter_stage = 'ABROAD', status = 'FILTERED_OUT' WHERE id = ?", older);

        var mix = analytics.analytics().market().stageMix();

        assertThat(mix).hasSize(2);
        assertThat(mix)
                .extracting(MarketView.StageDay::day)
                .containsExactly(LocalDate.now().minusDays(3), LocalDate.now());
    }

    private long arrived(LocalDate day) {
        return arrived(day, "portal-a");
    }

    private long arrived(LocalDate day, String portal) {
        Long id = jdbc.queryForObject(
                """
                INSERT INTO offer (source_id, external_id, title, url, portal, fingerprint, status, ingested_at)
                VALUES (?, ?, 'Java Entwickler', 'https://example.invalid/x', ?, 'fp', 'PASSED', ?)
                RETURNING id
                """,
                Long.class,
                sourceId,
                "ext-" + System.nanoTime(),
                portal,
                java.sql.Timestamp.valueOf(day.atTime(9, 0)));
        return id;
    }

    private void arrivedWithoutPublicationDate() {
        arrived(LocalDate.now());
    }

    private long published(LocalDate day) {
        long id = arrived(LocalDate.now());
        jdbc.update("UPDATE offer SET published_on = ? WHERE id = ?", day, id);
        return id;
    }

    private long scored(int value) {
        long id = arrived(LocalDate.now());
        jdbc.update(
                "UPDATE offer SET score_value = ?, score_band = 'REVIEW', scored_at = now() WHERE id = ?", value, id);
        return id;
    }

    private long sent(long offerId, LocalDate sentOn) {
        return jdbc.queryForObject(
                "INSERT INTO application (offer_id, status, sent_on) VALUES (?, 'SENT', ?) RETURNING id",
                Long.class,
                offerId,
                sentOn);
    }

    private void moved(long applicationId, ApplicationStatus to) {
        jdbc.update(
                "INSERT INTO application_event (application_id, from_status, to_status) VALUES (?, null, ?)",
                applicationId,
                to.name());
    }

    private void movedOn(long applicationId, ApplicationStatus to, LocalDate day) {
        jdbc.update(
                """
                INSERT INTO application_event (application_id, from_status, to_status, recorded_at)
                VALUES (?, 'SENT', ?, ?)
                """,
                applicationId,
                to.name(),
                java.sql.Timestamp.valueOf(day.atTime(12, 0)));
    }
}
