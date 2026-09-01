package de.codeministry.leadgen.offer;

import static org.assertj.core.api.Assertions.assertThat;

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

        assertThat(offers.shortlist())
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

        var entry = offers.shortlist().getFirst();

        assertThat(offers.shortlist()).hasSize(1);
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

        var score = offers.shortlist().getFirst().score();

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

        var score = offers.shortlist().getFirst().score();

        assertThat(score.value()).isNull();
        assertThat(score.reasons()).hasSize(1);
    }

    @Test
    void flagsAnIncompleteOfferWithoutDiscardingIt() {
        // Enrichment never discards: a 403 leaves the offer in with a note saying why, and
        // scoring then judges an incomplete offer as incomplete.
        long id = passed("Senior Java Entwickler", 70);
        jdbc.update("UPDATE offer SET enriched_at = now(), enrichment_note = 'HTTP 403' WHERE id = ?", id);

        var entry = offers.shortlist().getFirst();

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
        assertThat(funnel.stages()).extracting("removed").containsExactly(1, 0, 0, 0, 0, 0, 0);
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
                        "contract-form",
                        "stale");
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
