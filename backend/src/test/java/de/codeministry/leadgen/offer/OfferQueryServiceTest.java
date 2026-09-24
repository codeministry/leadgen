/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.offer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.codeministry.leadgen.Databases;
import de.codeministry.leadgen.config.ConfigFixtures;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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
 * What the shortlist screen reads: survivors, their reasons, and their duplicate cluster.
 */
@SpringBootTest
@Testcontainers
class OfferQueryServiceTest {

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
    private OfferQueryService offers;

    @Autowired
    private JdbcTemplate jdbc;

    private long sourceId;

    @Test
    void walksThePagesWithoutRepeatingOrSkippingAnOffer() {
        // Keyset, not offset: an offset re-sorts everything before it on every page and
        // slides by one whenever a run rewrites a score between two requests.
        for (int i = 0; i < 7; i++) {
            passed("Java Entwickler " + i, 90 - i);
        }

        var first = offers.shortlist(ShortlistQuery.first().withLimit(3));
        var second = offers.shortlist(ShortlistQuery.first().withLimit(3).withCursor(first.nextCursor()));
        var third = offers.shortlist(ShortlistQuery.first().withLimit(3).withCursor(second.nextCursor()));

        assertThat(first.entries()).hasSize(3);
        assertThat(second.entries()).hasSize(3);
        assertThat(third.entries()).hasSize(1);
        // The last page came back short, so there is nothing to ask for after it.
        assertThat(third.nextCursor()).isNull();
        assertThat(ids(first)).doesNotContainAnyElementsOf(ids(second));
        assertThat(ids(first)).hasSize(3).doesNotContainAnyElementsOf(ids(third));
    }

    @Test
    void keepsTwoOffersWithTheSameScoreOnEitherSideOfABoundary() {
        // Score alone is not a key: seven offers at 80 would let a page boundary fall inside
        // a tie, and the same row could come back on both pages or on neither.
        for (int i = 0; i < 4; i++) {
            passed("Gleichstand " + i, 80);
        }

        var first = offers.shortlist(ShortlistQuery.first().withLimit(2));
        var second = offers.shortlist(ShortlistQuery.first().withLimit(2).withCursor(first.nextCursor()));

        assertThat(ids(first)).doesNotContainAnyElementsOf(ids(second));
        assertThat(second.entries()).hasSize(2);
    }

    @Test
    void walksThroughOffersThatShareOneIngestTimestampExactly() {
        // What an ingest batch produces: `now()` is the transaction's clock, so every row
        // one batch writes carries the same `ingested_at` to the microsecond. A cursor
        // that truncates the value names an instant before the row it came from, and the
        // next page's `<` then drops the rest of the batch without a word.
        for (int i = 0; i < 4; i++) {
            passed("Gleiche Sekunde " + i, 80);
        }
        jdbc.update("UPDATE offer SET ingested_at = timestamptz '2026-09-02 08:00:00.123456+02'");

        var first = offers.shortlist(ShortlistQuery.first().withLimit(2));
        var second = offers.shortlist(ShortlistQuery.first().withLimit(2).withCursor(first.nextCursor()));

        assertThat(first.entries()).hasSize(2);
        assertThat(second.entries()).hasSize(2);
        assertThat(ids(first)).doesNotContainAnyElementsOf(ids(second));
    }

    @Test
    void countsWhatTheFiltersMatchAndWhatTheyWereNarrowedFrom() {
        passed("Senior Java Entwickler", 88);
        passed("Angular Entwickler", 40);
        rejected("Java Entwickler in Zürich");

        var page = offers.shortlist(
                new ShortlistQuery("angular", null, null, false, null, null, null, null, false, false, null, 0));

        assertThat(page.matched()).isEqualTo(1);
        assertThat(page.total()).isEqualTo(2);
    }

    @Test
    void countsTheUnscoredAcrossTheMatchAndNotAcrossThePage() {
        // Counted in the browser this was a count of the loaded entries, printed beside a
        // sentence about the whole list — so it shrank as the reader scrolled.
        passed("Senior Java Entwickler", 88);
        passed("Java Entwickler", null);
        passed("Angular Entwickler", null);

        var page = offers.shortlist(ShortlistQuery.first().withLimit(1));

        assertThat(page.entries()).hasSize(1);
        assertThat(page.unscored()).isEqualTo(2);
    }

    @Test
    void searchesTheTagsAsWellAsTheTitleAndTheDescription() {
        long tagged = passed("Entwickler", 70);
        jdbc.update("UPDATE offer SET tags = ARRAY['Kubernetes'] WHERE id = ?", tagged);
        passed("Anderer Entwickler", 60);

        var page = offers.shortlist(
                new ShortlistQuery("kubernetes", null, null, false, null, null, null, null, false, false, null, 0));

        assertThat(page.entries()).extracting(entry -> entry.offer().id()).containsExactly(tagged);
    }

    @Test
    void searchesTheSegmentedAdvertAndNotOnlyTheTeaser() {
        // The defect this fixes: the tool fetched the advert, stripped its furniture, stored
        // it, and then searched the newsletter teaser.
        long segmented = passed("Entwickler", 70);
        segment(segmented, block("CONTENT", "Ablösung eines Kernbankensystems in Kubernetes."));
        passed("Anderer Entwickler", 60);

        var page = offers.shortlist(search("kernbankensystem"));

        assertThat(page.entries()).extracting(entry -> entry.offer().id()).containsExactly(segmented);
    }

    @Test
    void doesNotSearchThePortalFurnitureTheContentStageTookOut() {
        // `full_text` still carries the agency's own signature. Searching it unconditionally
        // would match every advert that agency ever posted, which is the reason the search
        // goes through the CONTENT blocks rather than through the whole page.
        long segmented = passed("Entwickler", 70);
        segment(
                segmented,
                block("CONTENT", "Ablösung eines Monolithen."),
                block("AGENCY", "Acme Consulting GmbH, Amtsgericht Köln HRB 12345."));

        assertThat(offers.shortlist(search("amtsgericht")).entries()).isEmpty();
        assertThat(offers.shortlist(search("monolithen")).entries())
                .extracting(entry -> entry.offer().id())
                .containsExactly(segmented);
    }

    @Test
    void fallsBackToTheWholePageForAnAdvertThatWasNeverSegmented() {
        // `ContentText.of`'s own fallback: an advert with no blocks has `full_text` and
        // nothing else, so refusing to search it would hide what the tool did fetch.
        long fetched = passed("Entwickler", 70);
        jdbc.update("UPDATE offer SET full_text = ? WHERE id = ?", "Migration nach Kubernetes.", fetched);

        assertThat(offers.shortlist(search("kubernetes")).entries())
                .extracting(entry -> entry.offer().id())
                .containsExactly(fetched);
    }

    @Test
    void bandsOnTheConfiguredThresholdsRatherThanOnANumberInTheRequest() {
        // The boundaries are not in the query on purpose: a band named by the browser would
        // be the browser deciding what a shortlist is.
        passed("Stark", 88);
        passed("Mittel", 55);
        passed("Schwach", 10);

        assertThat(offers.shortlist(ShortlistQuery.first().withScore(band("shortlist")))
                        .entries())
                .extracting(entry -> entry.offer().title())
                .containsExactly("Stark");
        assertThat(offers.shortlist(ShortlistQuery.first().withScore(band("review")))
                        .entries())
                .extracting(entry -> entry.offer().title())
                .containsExactly("Mittel");
        assertThat(offers.shortlist(ShortlistQuery.first().withScore(band("discarded")))
                        .entries())
                .extracting(entry -> entry.offer().title())
                .containsExactly("Schwach");
    }

    @Test
    void offersEveryPortalOnTheShortlistAndNotOnlyOnThePage() {
        // Derived from the page, the dropdown would offer fewer choices the further you
        // scrolled — and it filters on a duplicate's portal too, so it has to list those.
        long primary = passed("Senior Java Entwickler", 88);
        duplicateOf(primary, "portal-c", "Zweite Agentur");

        var page = offers.shortlist(ShortlistQuery.first().withLimit(1));

        assertThat(page.portals()).contains("portal-c");
        assertThat(offers.shortlist(ShortlistQuery.first().withPortals(List.of("portal-c")))
                        .entries())
                .extracting(entry -> entry.offer().id())
                .containsExactly(primary);
    }

    private static List<Long> ids(ShortlistPage page) {
        return page.entries().stream().map(entry -> entry.offer().id()).toList();
    }

    /**
     * The unfiltered first page, which is what every case here was written against.
     */
    private List<ShortlistEntry> shortlist() {
        return offers.shortlist(ShortlistQuery.first()).entries();
    }

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM offer_score_reason");
        jdbc.update("DELETE FROM offer");
        jdbc.update("DELETE FROM source");
        sourceId =
                jdbc.queryForObject("INSERT INTO source (name, kind) VALUES ('test', 'file') RETURNING id", Long.class);
    }

    @Test
    void ranksTheSurvivorsAndLeavesOutWhatTheFilterRejected() {
        long strong = passed("Senior Java Entwickler", 88);
        long weak = passed("Java Entwickler", 64);
        rejected("Java Entwickler in Zürich");

        assertThat(shortlist()).extracting(entry -> entry.offer().id()).containsExactly(strong, weak);
    }

    @Test
    void collapsesOneProjectAdvertisedByThreePortalsIntoOneEntryThatNamesThem() {
        // 14.0 % of the corpus reaches the pipeline more than once. A shortlist showing the
        // same project three times is one nobody finishes reading.
        long primary = passed("Senior Java Entwickler", 88);
        duplicateOf(primary, "portal-b", "Zweite Agentur");
        duplicateOf(primary, "portal-e", null);

        var entry = shortlist().getFirst();

        assertThat(shortlist()).hasSize(1);
        assertThat(entry.sources())
                .extracting(OfferSourceRef::portal)
                .containsExactly("portal-a", "portal-b", "portal-e");
    }

    @Test
    void carriesEveryReasonInTheOrderItWasScored() {
        // A number without a reason gets ignored within a week.
        long id = passed("Senior Java Entwickler", 88);
        reason(id, "core_skill_overlap", "Java, Spring Boot, Kafka", 45, 0);
        reason(id, "anti_skill", "SAP", -30, 1);

        var score = shortlist().getFirst().score();

        assertThat(score.value()).isEqualTo(88);
        assertThat(score.reasons()).extracting("factor").containsExactly("core_skill_overlap", "anti_skill");
        assertThat(score.reasons().getLast().points()).isEqualTo(-30);
    }

    @Test
    void keepsTheReasonsWhenThereIsNoTotal() {
        // Without a language model the shortlist still exists, only unranked. Withholding
        // the reasons as well would leave the operator nothing to judge by.
        long id = passed("Senior Java Entwickler", null);
        reason(id, "core_skill_overlap", "Java, Spring Boot", 45, 0);

        var score = shortlist().getFirst().score();

        assertThat(score.value()).isNull();
        assertThat(score.reasons()).hasSize(1);
    }

    @Test
    void flagsAnIncompleteOfferWithoutDiscardingIt() {
        // Enrichment never discards: a 403 leaves the offer in with a note saying why, and
        // scoring then judges an incomplete offer as incomplete.
        long id = passed("Senior Java Entwickler", 70);
        jdbc.update("UPDATE offer SET enriched_at = now(), enrichment_note = 'HTTP 403' WHERE id = ?", id);

        var entry = shortlist().getFirst();

        assertThat(entry.flags().incomplete()).isTrue();
        assertThat(entry.flags().remoteUnknown()).isTrue();
    }

    @Test
    void countsTheFunnelAgainstTheSameSetOnBothSides() {
        // Deduplication runs before the filter and a rejection is written on duplicates
        // too. Counting every rejection against a primaries-only total made the rail
        // report a negative number of survivors.
        long primary = passed("Senior Java Entwickler", 88);
        duplicateOf(primary, "portal-b", null);
        jdbc.update("UPDATE offer SET status = 'REJECTED', filter_stage = 'ABROAD' WHERE duplicate_of_id IS NOT NULL");
        rejected("Java Entwickler in Zürich");

        var funnel = offers.funnel();

        assertThat(funnel.total()).isEqualTo(2);
        assertThat(funnel.stages()).extracting("removed").containsExactly(1, 0, 0, 0, 0, 0);
        assertThat(funnel.survived()).isEqualTo(1);
        assertThat(funnel.stages().stream().mapToInt(stage -> stage.removed()).sum() + funnel.survived())
                .isEqualTo(funnel.total());
    }

    @Test
    void namesTheStagesInTheOrderTheyRun() {
        // The order is the meaning: an offer stops at the first stage that rejects it,
        // which is the only reason the counts sum to the total.
        assertThat(offers.funnel().stages())
                .extracting("id")
                .containsExactly(
                        "abroad", "remote-share", "out-of-reach", "role-or-stack", "no-core-skill", "contract-form");
    }

    @Test
    void keepsTheArchiveOutOfTheWorkingListAndOffersItOnRequest() {
        long working = passed("Aktuell", 88);
        long archived = passed("Archiviert", 90);
        jdbc.update("UPDATE offer SET archived_at = now(), archive_source = 'AGE' WHERE id = ?", archived);

        var list = offers.shortlist(ShortlistQuery.first());
        var archive = offers.shortlist(
                new ShortlistQuery(null, null, null, true, null, null, null, null, false, false, null, 0));

        assertThat(ids(list)).containsExactly(working);
        assertThat(list.matched()).isEqualTo(1);
        // The archive is what the working list was narrowed from only in the sense that
        // both are sets of offers. Each side counts its own.
        assertThat(list.total()).isEqualTo(1);
        assertThat(ids(archive)).containsExactly(archived);
        assertThat(archive.total()).isEqualTo(1);
    }

    @Test
    void offersOnlyThePortalsOfTheSideBeingRead() {
        // The dropdown built from the working list must not offer a portal that only ever
        // appears in the archive: choosing it would produce an empty list and no reason.
        passed("Aktuell", 88);
        long archived = jdbc.queryForObject("""
            INSERT INTO offer (source_id, external_id, title, url, fingerprint, status, portal,
                               archived_at, archive_source)
            VALUES (?, 'a', 'Archiviert', 'https://example.invalid/a', 'archiviert', 'PASSED', 'portal-c',
                    now(), 'AGE')
            RETURNING id
            """, Long.class, sourceId);

        assertThat(offers.shortlist(ShortlistQuery.first()).portals()).containsExactly("portal-a");
        assertThat(offers.shortlist(new ShortlistQuery(
                                null, null, null, true, null, null, null, null, false, false, null, 0))
                        .portals())
                .containsExactly("portal-c");
        assertThat(archived).isPositive();
    }

    @Test
    void countsTheFunnelOverTheWorkingListAndTheArchiveBesideIt() {
        // Both sides of the subtraction, or the rail claims a negative survivor count —
        // the same defect duplicates once produced, and after a week the archive holds
        // most of the table.
        passed("Aktuell", 88);
        long archived = passed("Archiviert", 90);
        jdbc.update("UPDATE offer SET archived_at = now(), archive_source = 'AGE' WHERE id = ?", archived);
        long stale = rejected("Java Entwickler in Zürich");
        jdbc.update("UPDATE offer SET archived_at = now(), archive_source = 'AGE' WHERE id = ?", stale);

        var funnel = offers.funnel();

        assertThat(funnel.archived()).isEqualTo(2);
        assertThat(funnel.total()).isEqualTo(1);
        // The invariant worth checking whenever either number looks wrong.
        assertThat(funnel.survived())
                .isEqualTo(offers.shortlist(ShortlistQuery.first()).total());
    }

    @Test
    void showsAnArchivedOfferWhenItIsAskedForById() {
        // Same argument as a rejected one: the detail is how somebody looks at an offer
        // that is not on the list and decides whether it should come back.
        long id = passed("Archiviert", 88);
        jdbc.update("UPDATE offer SET archived_at = now(), archive_source = 'MANUAL' WHERE id = ?", id);

        assertThat(offers.find(id)).isPresent();
    }

    @Test
    void showsAnOfferTheFilterRejectedWhenItIsAskedForById() {
        // The detail is also how somebody looks at a rejection and asks whether the rule
        // was right. Restricting it to survivors would make that impossible.
        long id = rejected("Java Entwickler in Zürich");

        assertThat(offers.find(id)).isPresent();
        assertThat(offers.find(id).orElseThrow().score().hardPass()).isFalse();
        assertThat(offers.find(999_999L)).isEmpty();
    }

    // ---- sorting -------------------------------------------------------------------

    @Test
    void sortsTheShortlistByTheKeyTheRequestNames() {
        long soon = passed("Startet bald", 40);
        long later = passed("Startet später", 90);
        starts(soon, LocalDate.now().plusDays(3));
        starts(later, LocalDate.now().plusDays(300));

        assertThat(ids(offers.shortlist(ShortlistQuery.first()))).containsExactly(later, soon);
        assertThat(ids(offers.shortlist(ShortlistQuery.first().withSort(ShortlistSort.START))))
                .containsExactly(soon, later);
    }

    @Test
    void keepsTwoOffersWithTheSameStartDateOnEitherSideOfABoundary() {
        // The same tie the score test pins, one key over. With a uniform direction the
        // tuple is the only thing keeping the boundary honest: a `<` where a `>` belongs
        // gives an empty second page, which is loud, while an ORDER BY that descends against
        // a comparison that ascends repeats page one, which is not.
        var all = new ArrayList<Long>();
        for (int i = 0; i < 4; i++) {
            long id = passed("Gleicher Start " + i, 50);
            starts(id, LocalDate.of(2026, 11, 1));
            all.add(id);
        }

        var query = ShortlistQuery.first().withSort(ShortlistSort.START).withLimit(2);
        var first = offers.shortlist(query);
        var second = offers.shortlist(query.withCursor(first.nextCursor()));

        assertThat(first.entries()).hasSize(2);
        assertThat(second.entries()).hasSize(2);
        assertThat(ids(first)).doesNotContainAnyElementsOf(ids(second));
        assertThat(ids(first)).containsAll(List.of()).hasSize(2);
        assertThat(walk(query)).containsExactlyInAnyOrderElementsOf(all);
    }

    @Test
    void keepsTwoOffersWithTheSameDeadlineOnEitherSideOfABoundary() {
        var all = new ArrayList<Long>();
        for (int i = 0; i < 4; i++) {
            long id = passed("Gleiche Frist " + i, 50);
            applyBy(id, LocalDate.now().plusDays(20));
            all.add(id);
        }

        assertThat(walk(ShortlistQuery.first().withSort(ShortlistSort.DEADLINE).withLimit(2)))
                .containsExactlyInAnyOrderElementsOf(all);
    }

    @Test
    void keepsTwoOffersWithTheSameDurationOnEitherSideOfABoundary() {
        var all = new ArrayList<Long>();
        for (int i = 0; i < 4; i++) {
            long id = passed("Gleiche Dauer " + i, 50);
            durationMonths(id, 6);
            all.add(id);
        }

        assertThat(walk(ShortlistQuery.first().withSort(ShortlistSort.DURATION).withLimit(2)))
                .containsExactlyInAnyOrderElementsOf(all);
    }

    @Test
    void putsTheShortestDurationFirstAndStillKeepsTheUnstatedLast() {
        // The sentinel that moved, and the reason it had to. Both duration sorts read one
        // column, so held on the kind the reverse would have inherited -1 — which puts "not
        // stated" last under DESC and *first* under ASC. Two directions over one column is the
        // only shape in which that mistake is invisible, because each sort looks right on its
        // own. This is what fails the day the sentinel moves back onto `Key`.
        long year = passed("Zwölf Monate", 50);
        long quarter = passed("Drei Monate", 50);
        long half = passed("Sechs Monate", 50);
        durationMonths(year, 12);
        durationMonths(quarter, 3);
        durationMonths(half, 6);
        long unstated = passed("Keine Dauer genannt", 50);

        assertThat(walk(ShortlistQuery.first()
                        .withSort(ShortlistSort.DURATION_SHORT)
                        .withLimit(2)))
                .containsExactly(quarter, half, year, unstated);
        assertThat(walk(ShortlistQuery.first().withSort(ShortlistSort.DURATION).withLimit(2)))
                .containsExactly(year, half, quarter, unstated);
    }

    @Test
    void walksNewestFirstAndNeedsNoSentinelToDoIt() {
        // The one key that cannot be unstated: `ingested_at` is NOT NULL from the baseline and
        // the upsert writes it, so `fresh` is the only sort whose expression is the bare column
        // with no coalesce around it. It is also already every other tuple's tiebreaker, which
        // is why its walk compares the same column twice — and why a page boundary here is
        // still the thing worth pinning.
        var inserted = new ArrayList<Long>();
        for (int i = 0; i < 5; i++) {
            inserted.add(passed("Reingekommen " + i, 90 - i));
        }

        assertThat(walk(ShortlistQuery.first().withSort(ShortlistSort.FRESH).withLimit(2)))
                .containsExactlyElementsOf(inserted.reversed());
    }

    @ParameterizedTest
    @EnumSource(value = ShortlistSort.class, names = "FRESH", mode = EnumSource.Mode.EXCLUDE)
    void keepsTheUnstatedAtTheEndOfEverySortAndNeverDropsIt(ShortlistSort sort) {
        // Excluded by name and not by a predicate, so a sort added later is in this test until
        // somebody deliberately takes it out. FRESH is out because its key is NOT NULL by
        // construction: there is nothing to fold to the end, and the fixture's "states nothing"
        // offers are simply the newest rows. `walksNewestFirstAndNeedsNoSentinelToDoIt` covers
        // it instead.

        // The centrepiece. SQL row comparison yields NULL the moment any element is NULL, so
        // a nullable sort column walked with `NULLS LAST` shows its unstated offers at the
        // end of page one and then loses every one of them on page two — while the match
        // count still counts them. The coalesce sentinel is what stops that, and this is what
        // fails the day somebody replaces it.
        var stated = new ArrayList<Long>();
        var unstated = new ArrayList<Long>();
        for (int i = 0; i < 3; i++) {
            long id = passed("Vollständig " + i, 80 - i);
            starts(id, LocalDate.now().plusDays(10L + i));
            applyBy(id, LocalDate.now().plusDays(5L + i));
            durationMonths(id, 3 + i);
            stated.add(id);
            // Nothing but a title: no score, no start, no deadline, no duration.
            unstated.add(bare("Nichts gesagt " + i));
        }

        var walked = walk(ShortlistQuery.first().withSort(sort).withLimit(2));

        assertThat(walked).containsExactlyInAnyOrderElementsOf(concat(stated, unstated));
        assertThat(walked.subList(walked.size() - 3, walked.size())).containsExactlyInAnyOrderElementsOf(unstated);
    }

    @Test
    void refusesACursorMintedUnderADifferentSort() {
        // Identical bytes, different meaning: a score of 88 read as an epoch day is March
        // 1970, and the row comparison then answers with an arbitrary slice and no error.
        for (int i = 0; i < 4; i++) {
            passed("Java Entwickler " + i, 90 - i);
        }
        String cursor = offers.shortlist(ShortlistQuery.first().withLimit(2)).nextCursor();

        assertThatThrownBy(() -> offers.shortlist(ShortlistQuery.first()
                        .withSort(ShortlistSort.START)
                        .withLimit(2)
                        .withCursor(cursor)))
                .isInstanceOf(BadShortlistRequest.class)
                .hasMessageContaining("sort=score")
                .hasMessageContaining("sort=start");
    }

    @Test
    void treatsACursorFromTheOldThreePartFormAsABadRequest() {
        // A link somebody shared yesterday carries one. It used to be an
        // ArrayIndexOutOfBoundsException, which is a 500.
        assertThatThrownBy(() -> offers.shortlist(ShortlistQuery.first().withCursor("88|1756800000123456|4211")))
                .isInstanceOf(BadShortlistRequest.class);
        assertThatThrownBy(() -> offers.shortlist(ShortlistQuery.first().withCursor("score|x|y|z")))
                .isInstanceOf(BadShortlistRequest.class);
    }

    // ---- the three filters ---------------------------------------------------------

    @Test
    void partitionsTheShortlistIntoFourStartWindowsThatAddUpToIt() {
        starts(passed("Läuft schon", 50), LocalDate.now().minusDays(4));
        starts(passed("In einer Woche", 50), LocalDate.now().plusDays(7));
        starts(passed("Am Rand", 50), LocalDate.now().plusDays(30));
        starts(passed("Irgendwann", 50), LocalDate.now().plusDays(90));
        passed("Kein Datum", 50);

        int whole = offers.shortlist(ShortlistQuery.first()).matched();
        int sum = 0;
        for (StartWindow window : List.of(StartWindow.NOW, StartWindow.SOON, StartWindow.LATER, StartWindow.UNKNOWN)) {
            sum += offers.shortlist(inWindow(window)).matched();
        }

        assertThat(sum).isEqualTo(whole);
        assertThat(offers.shortlist(inWindow(StartWindow.UNKNOWN)).matched()).isEqualTo(1);
        assertThat(offers.shortlist(inWindow(StartWindow.NOW)).matched()).isEqualTo(1);
    }

    @Test
    void keepsTheBoundaryDayInsideTheNearWindow() {
        starts(passed("Genau dreißig Tage", 50), LocalDate.now().plusDays(30));

        assertThat(offers.shortlist(inWindow(StartWindow.SOON)).matched()).isEqualTo(1);
        assertThat(offers.shortlist(inWindow(StartWindow.LATER)).matched()).isZero();
    }

    @Test
    void excludesAnOfferWhoseDurationNobodyStatedFromAMinimum() {
        // The opposite null treatment from the deadline filter below, and the pair is pinned
        // together on purpose: "at least six months" is a claim about the offer, and an offer
        // that says nothing does not make it.
        durationMonths(passed("Zwölf Monate", 50), 12);
        durationMonths(passed("Drei Monate", 50), 3);
        passed("Keine Dauer genannt", 50);

        var page = offers.shortlist(
                new ShortlistQuery(null, null, null, false, null, null, null, 6, false, false, null, 0));

        assertThat(page.matched()).isEqualTo(1);
        assertThat(page.entries().getFirst().offer().durationMonths()).isEqualTo(12);
    }

    @Test
    void keepsAnOfferWithNoDeadlineOnTheOpenListAndDropsOneThatHasPassed() {
        // "Still open" is the absence of proof that it closed, so an advert that states no
        // deadline has not missed one.
        applyBy(passed("Frist nächste Woche", 50), LocalDate.now().plusDays(7));
        applyBy(passed("Frist vorbei", 50), LocalDate.now().minusDays(1));
        long unstated = passed("Keine Frist genannt", 50);

        var page = offers.shortlist(
                new ShortlistQuery(null, null, null, false, null, null, null, null, true, false, null, 0));

        assertThat(page.matched()).isEqualTo(2);
        assertThat(ids(page)).contains(unstated);
    }

    @Test
    void matchesAnyOfSeveralPortalsAndStillReachesThroughADuplicate() {
        // One portal or all of them was the whole choice before this, which on a tool that is
        // meant to read several sources is a filter that can only ever answer about one of
        // them. The reach through a duplicate is the part that has to survive the plural: a
        // project on portal-c is on portal-c even when portal-a holds the primary.
        long onA = passed("Nur auf A", 80);
        long viaC = passed("Primär auf A, auch auf C", 70);
        duplicateOf(viaC, "portal-c", "Zweite Agentur");
        passed("Auch nur auf A", 60);
        jdbc.update("UPDATE offer SET portal = 'portal-b' WHERE id = ?", onA);

        var page = offers.shortlist(ShortlistQuery.first().withPortals(List.of("portal-b", "portal-c")));

        assertThat(ids(page)).containsExactlyInAnyOrder(onA, viaC);
        assertThat(page.matched()).isEqualTo(2);
    }

    @Test
    void takesOnePortalAsAListOfOneSoEveryOlderLinkStillMeansWhatItMeant() {
        long primary = passed("Senior Java Entwickler", 88);
        duplicateOf(primary, "portal-c", "Zweite Agentur");
        passed("Woanders", 70);

        assertThat(ids(offers.shortlist(ShortlistQuery.first().withPortals(List.of("portal-c")))))
                .containsExactly(primary);
    }

    @Test
    void excludesAnOfferNobodyJudgedFromAScoreRange() {
        // The same null treatment `minMonths` has, one axis over: "at least sixty" is a claim
        // about the offer, and an offer nobody judged does not make it. `scoreState=unscored`
        // is how that set is asked for instead, which is the next test.
        long strong = passed("Stark", 88);
        passed("Mittel", 55);
        long unjudged = bare("Nie bewertet");

        var page = offers.shortlist(ShortlistQuery.first().withScore(new ScoreFilter(null, 60, null, null)));

        assertThat(ids(page)).containsExactly(strong).doesNotContain(unjudged);
        assertThat(page.matched()).isEqualTo(1);
    }

    @Test
    void closesTheRangeAtBothEndsInclusively() {
        long low = passed("Genau vierzig", 40);
        long high = passed("Genau achtzig", 80);
        passed("Darüber", 81);
        passed("Darunter", 39);

        assertThat(ids(offers.shortlist(ShortlistQuery.first().withScore(new ScoreFilter(null, 40, 80, null)))))
                .containsExactlyInAnyOrder(low, high);
    }

    @Test
    void returnsExactlyTheOffersTheUnscoredCountHasAlwaysBeenNaming() {
        // The number rode along with every match count and was the one figure beside the list
        // nobody could click. The assertion is that the two agree: the count of the unfiltered
        // match and the size of the filtered one are the same set, or the figure is pointing
        // somewhere else.
        passed("Stark", 88);
        long first = bare("Nie bewertet");
        long second = bare("Auch nie bewertet");

        int unscored = offers.shortlist(ShortlistQuery.first()).unscored();
        var page = offers.shortlist(
                ShortlistQuery.first().withScore(new ScoreFilter(null, null, null, ScoreState.UNSCORED)));

        assertThat(unscored).isEqualTo(2);
        assertThat(ids(page)).containsExactlyInAnyOrder(first, second);
        assertThat(page.matched()).isEqualTo(unscored);
        assertThat(ids(offers.shortlist(
                        ShortlistQuery.first().withScore(new ScoreFilter(null, null, null, ScoreState.SCORED)))))
                .doesNotContain(first, second);
    }

    @Test
    void countsTheMatchAgainstTheFiltersAndNotAgainstThePage() {
        // The defect this change sits on top of: the cursor clause used to be formatted into
        // MATCHED as well, so `matched` counted the rows *after* the cursor and the number
        // beside the list shrank as the reader scrolled — which is exactly why the count was
        // moved to the server in the first place.
        for (int i = 0; i < 5; i++) {
            starts(passed("Java Entwickler " + i, 90 - i), LocalDate.now().plusDays(3));
        }

        var query = ShortlistQuery.first().withLimit(2);
        var first = offers.shortlist(query);
        var second = offers.shortlist(query.withCursor(first.nextCursor()));

        assertThat(second.matched()).isEqualTo(first.matched()).isEqualTo(5);
        assertThat(second.total()).isEqualTo(first.total());

        // And a filter moves the match without moving what it was narrowed from.
        var narrowed = offers.shortlist(inWindow(StartWindow.LATER));
        assertThat(narrowed.matched()).isZero();
        assertThat(narrowed.total()).isEqualTo(first.total());
        assertThat(narrowed.portals()).isEqualTo(first.portals());
    }

    @Test
    void narrowsToOffersWhoseStoredReasonsNameTheTopicInEveryBand() {
        // The point of the filter: an offer the topic makes interesting is findable whether
        // or not the score carried it over the line. The rows are written the way ScoreWriter
        // writes them, topic in its own column; the scorer's half is ScoringWithTopicsTest.
        long shortlisted = passed("Oben", 82);
        long review = passed("Mitte", 55);
        long discarded = passed("Unten", 31);
        long unscored = bare("Nie bewertet");
        long other = passed("Anderes Thema", 60);
        jdbc.update("UPDATE offer SET score_band = 'SHORTLISTED' WHERE id = ?", shortlisted);
        jdbc.update("UPDATE offer SET score_band = 'REVIEW' WHERE id = ?", review);
        jdbc.update("UPDATE offer SET score_band = 'DISCARDED' WHERE id = ?", discarded);
        for (long id : List.of(shortlisted, review, discarded, unscored)) {
            topicReason(id, "interest_fit", "Wanted topic", 12);
        }
        topicReason(other, "interest_fit", "Another topic", 12);

        var query = ShortlistQuery.first().withLimit(2).withTopic("Wanted topic");

        assertThat(walk(query)).containsExactlyInAnyOrder(shortlisted, review, discarded, unscored);
        assertThat(offers.shortlist(query).matched()).isEqualTo(4);
        assertThat(ids(offers.shortlist(ShortlistQuery.first().withTopic("Nobody's topic"))))
                .isEmpty();
    }

    // ---- fixtures ------------------------------------------------------------------

    private void topicReason(long offerId, String factor, String topic, int points) {
        jdbc.update(
                "INSERT INTO offer_score_reason (offer_id, factor, label, points, max_points, topic, position)"
                        + " VALUES (?, ?, ?, ?, 0, ?, 0)",
                offerId,
                factor,
                "interest: " + topic,
                points,
                topic);
    }

    private static ShortlistQuery inWindow(StartWindow window) {
        return new ShortlistQuery(null, null, null, false, null, window, null, null, false, false, null, 0);
    }

    private static ScoreFilter band(String name) {
        return new ScoreFilter(name, null, null, null);
    }

    private static List<Long> concat(List<Long> a, List<Long> b) {
        var all = new ArrayList<>(a);
        all.addAll(b);
        return all;
    }

    /**
     * Every page of a query, in order. What a reader scrolling to the end actually gets, and
     * the only way to see a row the second page dropped.
     */
    private List<Long> walk(ShortlistQuery query) {
        var all = new ArrayList<Long>();
        String cursor = null;
        for (int guard = 0; guard < 20; guard++) {
            var page = offers.shortlist(query.withCursor(cursor));
            all.addAll(ids(page));
            cursor = page.nextCursor();
            if (cursor == null) {
                return all;
            }
        }
        throw new IllegalStateException("the walk did not end; the cursor is not advancing");
    }

    private void starts(long id, LocalDate day) {
        jdbc.update("UPDATE offer SET starts_on = ? WHERE id = ?", day, id);
    }

    private void applyBy(long id, LocalDate day) {
        jdbc.update("UPDATE offer SET apply_by = ? WHERE id = ?", day, id);
    }

    private void durationMonths(long id, int months) {
        jdbc.update("UPDATE offer SET duration_months = ? WHERE id = ?", months, id);
    }

    /**
     * A survivor that states nothing at all: no score, no start, no deadline, no duration.
     */
    @Test
    void marksAndFiltersWhatTheSimilarityPassOnlySuspected() {
        // The band between the two thresholds: close enough to be worth two eyes, not close
        // enough to take one of them off the list. So the row stays, carries a flag, and can
        // be asked for on its own.
        long older = passed("Senior Java Entwickler", 88);
        long suspected = passed("Java Entwickler Senior", 84);
        jdbc.update("UPDATE offer SET possible_duplicate_of_id = ? WHERE id = ?", older, suspected);

        var all = offers.shortlist(ShortlistQuery.first());
        assertThat(all.entries()).hasSize(2);
        assertThat(all.entries().stream()
                        .filter(entry -> entry.flags().possibleDuplicate())
                        .map(entry -> entry.offer().id()))
                .containsExactly(suspected);

        var only = offers.shortlist(
                new ShortlistQuery(null, null, null, false, null, null, null, null, false, true, null, 0));
        assertThat(only.entries()).hasSize(1);
        assertThat(only.entries().getFirst().offer().id()).isEqualTo(suspected);
        // Still a working-list offer: the filter narrows what is shown and changes nothing
        // about what the offer is.
        assertThat(only.entries().getFirst().flags().possibleDuplicate()).isTrue();
    }

    private static ShortlistQuery search(String q) {
        return new ShortlistQuery(q, null, null, false, null, null, null, null, false, false, null, 0);
    }

    /**
     * One entry of `content_blocks` as `ContentService` writes it. The kind is what decides
     * whether the search may read the text, so a fixture that sets `full_text` alone cannot
     * prove anything about either half of this.
     */
    private static String block(String kind, String text) {
        return """
            {"index": 0, "kind": "%s", "text": "%s", "reason": "fixture", "by": "RULE"}""".formatted(kind, text);
    }

    private void segment(long offer, String... blocks) {
        jdbc.update(
                "UPDATE offer SET content_blocks = CAST(? AS jsonb), full_text = ?, content_at = now() WHERE id = ?",
                "[" + String.join(",", blocks) + "]",
                // The whole page, furniture included — which is exactly what the search must
                // not read once the blocks exist.
                "Acme Consulting GmbH, Amtsgericht Köln HRB 12345. Jetzt bewerben.",
                offer);
    }

    private long bare(String title) {
        return passed(title, null);
    }

    private long passed(String title, Integer score) {
        return jdbc.queryForObject(
                """
                INSERT INTO offer (source_id, external_id, title, description, url, fingerprint, status,
                                   score_value, portal, agency, tags)
                VALUES (?, ?, ?, 'Ablösung eines Monolithen.', ?, ?, 'PASSED', ?, 'portal-a', 'Acme Consulting GmbH',
                        ARRAY['Java','Spring Boot'])
                RETURNING id
                """,
                Long.class,
                sourceId,
                title,
                title,
                "https://example.invalid/" + title.hashCode(),
                title.toLowerCase(),
                score);
    }

    private long rejected(String title) {
        return jdbc.queryForObject("""
            INSERT INTO offer (source_id, external_id, title, url, fingerprint, status, filter_stage)
            VALUES (?, ?, ?, 'https://example.invalid/x', ?, 'REJECTED', 'ABROAD')
            RETURNING id
            """, Long.class, sourceId, title, title, title.toLowerCase());
    }

    private void duplicateOf(long primary, String portal, String agency) {
        jdbc.update("""
            INSERT INTO offer (source_id, external_id, title, url, fingerprint, status, portal, agency,
                               duplicate_of_id)
            VALUES (?, ?, 'Senior Java Entwickler', ?, 'senior java entwickler', 'PASSED', ?, ?, ?)
            """, sourceId, portal + primary, "https://" + portal + "/x", portal, agency, primary);
    }

    private void reason(long offerId, String factor, String label, int points, int position) {
        jdbc.update(
                "INSERT INTO offer_score_reason (offer_id, factor, label, points, position) VALUES (?, ?, ?, ?, ?)",
                offerId,
                factor,
                label,
                points,
                position);
    }
}
