package de.codeministry.leadgen.offer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** What the shortlist screen reads: survivors, their reasons, and their duplicate cluster. */
@SpringBootTest
@Testcontainers
class OfferQueryServiceTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

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

        var first = offers.shortlist(new ShortlistQuery(null, null, null, false, null, 3));
        var second = offers.shortlist(new ShortlistQuery(null, null, null, false, first.nextCursor(), 3));
        var third = offers.shortlist(new ShortlistQuery(null, null, null, false, second.nextCursor(), 3));

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

        var first = offers.shortlist(new ShortlistQuery(null, null, null, false, null, 2));
        var second = offers.shortlist(new ShortlistQuery(null, null, null, false, first.nextCursor(), 2));

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

        var first = offers.shortlist(new ShortlistQuery(null, null, null, false, null, 2));
        var second = offers.shortlist(new ShortlistQuery(null, null, null, false, first.nextCursor(), 2));

        assertThat(first.entries()).hasSize(2);
        assertThat(second.entries()).hasSize(2);
        assertThat(ids(first)).doesNotContainAnyElementsOf(ids(second));
    }

    @Test
    void countsWhatTheFiltersMatchAndWhatTheyWereNarrowedFrom() {
        passed("Senior Java Entwickler", 88);
        passed("Angular Entwickler", 40);
        rejected("Java Entwickler in Zürich");

        var page = offers.shortlist(new ShortlistQuery("angular", null, null, false, null, 0));

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

        var page = offers.shortlist(new ShortlistQuery(null, null, null, false, null, 1));

        assertThat(page.entries()).hasSize(1);
        assertThat(page.unscored()).isEqualTo(2);
    }

    @Test
    void searchesTheTagsAsWellAsTheTitleAndTheDescription() {
        long tagged = passed("Entwickler", 70);
        jdbc.update("UPDATE offer SET tags = ARRAY['Kubernetes'] WHERE id = ?", tagged);
        passed("Anderer Entwickler", 60);

        var page = offers.shortlist(new ShortlistQuery("kubernetes", null, null, false, null, 0));

        assertThat(page.entries()).extracting(entry -> entry.offer().id()).containsExactly(tagged);
    }

    @Test
    void bandsOnTheConfiguredThresholdsRatherThanOnANumberInTheRequest() {
        // The boundaries are not in the query on purpose: a band named by the browser would
        // be the browser deciding what a shortlist is.
        passed("Stark", 88);
        passed("Mittel", 55);
        passed("Schwach", 10);

        assertThat(offers.shortlist(new ShortlistQuery(null, "shortlist", null, false, null, 0)).entries())
                .extracting(entry -> entry.offer().title())
                .containsExactly("Stark");
        assertThat(offers.shortlist(new ShortlistQuery(null, "review", null, false, null, 0)).entries())
                .extracting(entry -> entry.offer().title())
                .containsExactly("Mittel");
    }

    @Test
    void offersEveryPortalOnTheShortlistAndNotOnlyOnThePage() {
        // Derived from the page, the dropdown would offer fewer choices the further you
        // scrolled — and it filters on a duplicate's portal too, so it has to list those.
        long primary = passed("Senior Java Entwickler", 88);
        duplicateOf(primary, "gulp", "Zweite Agentur");

        var page = offers.shortlist(new ShortlistQuery(null, null, null, false, null, 1));

        assertThat(page.portals()).contains("gulp");
        assertThat(offers.shortlist(new ShortlistQuery(null, null, "gulp", false, null, 0)).entries())
                .extracting(entry -> entry.offer().id())
                .containsExactly(primary);
    }

    private static List<Long> ids(ShortlistPage page) {
        return page.entries().stream().map(entry -> entry.offer().id()).toList();
    }

    /** The unfiltered first page, which is what every case here was written against. */
    private List<ShortlistEntry> shortlist() {
        return offers.shortlist(new ShortlistQuery(null, null, null, false, null, 0)).entries();
    }

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM offer_score_reason");
        jdbc.update("DELETE FROM offer");
        jdbc.update("DELETE FROM source");
        sourceId = jdbc.queryForObject(
                "INSERT INTO source (name, kind) VALUES ('test', 'file') RETURNING id", Long.class);
    }

    @Test
    void ranksTheSurvivorsAndLeavesOutWhatTheFilterRejected() {
        long strong = passed("Senior Java Entwickler", 88);
        long weak = passed("Java Entwickler", 64);
        rejected("Java Entwickler in Zürich");

        assertThat(shortlist())
                .extracting(entry -> entry.offer().id())
                .containsExactly(strong, weak);
    }

    @Test
    void collapsesOneProjectAdvertisedByThreePortalsIntoOneEntryThatNamesThem() {
        // 12.3 % of the corpus reaches the pipeline more than once. A shortlist showing the
        // same project three times is one nobody finishes reading.
        long primary = passed("Senior Java Entwickler", 88);
        duplicateOf(primary, "freelance.de", "Zweite Agentur");
        duplicateOf(primary, "freelancermap", null);

        var entry = shortlist().getFirst();

        assertThat(shortlist()).hasSize(1);
        assertThat(entry.sources())
                .extracting(OfferSourceRef::portal)
                .containsExactly("FreelancerMap", "freelance.de", "freelancermap");
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
        duplicateOf(primary, "freelance.de", null);
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
                        "abroad",
                        "remote-share",
                        "out-of-reach",
                        "role-or-stack",
                        "no-core-skill",
                        "contract-form");
    }

    @Test
    void keepsTheArchiveOutOfTheWorkingListAndOffersItOnRequest() {
        long working = passed("Aktuell", 88);
        long archived = passed("Archiviert", 90);
        jdbc.update("UPDATE offer SET archived_at = now(), archive_source = 'AGE' WHERE id = ?", archived);

        var list = offers.shortlist(new ShortlistQuery(null, null, null, false, null, 0));
        var archive = offers.shortlist(new ShortlistQuery(null, null, null, true, null, 0));

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
        long archived = jdbc.queryForObject(
                """
                INSERT INTO offer (source_id, external_id, title, url, fingerprint, status, portal,
                                   archived_at, archive_source)
                VALUES (?, 'a', 'Archiviert', 'https://example.invalid/a', 'archiviert', 'PASSED', 'gulp',
                        now(), 'AGE')
                RETURNING id
                """,
                Long.class, sourceId);

        assertThat(offers.shortlist(new ShortlistQuery(null, null, null, false, null, 0)).portals())
                .containsExactly("FreelancerMap");
        assertThat(offers.shortlist(new ShortlistQuery(null, null, null, true, null, 0)).portals())
                .containsExactly("gulp");
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
                .isEqualTo(offers.shortlist(new ShortlistQuery(null, null, null, false, null, 0))
                        .total());
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

    private long passed(String title, Integer score) {
        return jdbc.queryForObject(
                """
                INSERT INTO offer (source_id, external_id, title, description, url, fingerprint, status,
                                   score_value, portal, agency, tags)
                VALUES (?, ?, ?, 'Ablösung eines Monolithen.', ?, ?, 'PASSED', ?, 'FreelancerMap', 'Etengo AG',
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
        return jdbc.queryForObject(
                """
                INSERT INTO offer (source_id, external_id, title, url, fingerprint, status, filter_stage)
                VALUES (?, ?, ?, 'https://example.invalid/x', ?, 'REJECTED', 'ABROAD')
                RETURNING id
                """,
                Long.class, sourceId, title, title, title.toLowerCase());
    }

    private void duplicateOf(long primary, String portal, String agency) {
        jdbc.update(
                """
                INSERT INTO offer (source_id, external_id, title, url, fingerprint, status, portal, agency,
                                   duplicate_of_id)
                VALUES (?, ?, 'Senior Java Entwickler', ?, 'senior java entwickler', 'PASSED', ?, ?, ?)
                """,
                sourceId, portal + primary, "https://" + portal + "/x", portal, agency, primary);
    }

    private void reason(long offerId, String factor, String label, int points, int position) {
        jdbc.update(
                "INSERT INTO offer_score_reason (offer_id, factor, label, points, position) VALUES (?, ?, ?, ?, ?)",
                offerId, factor, label, points, position);
    }
}
