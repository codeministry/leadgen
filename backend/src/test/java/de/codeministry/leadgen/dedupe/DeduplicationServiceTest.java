package de.codeministry.leadgen.dedupe;

import static org.assertj.core.api.Assertions.assertThat;

import de.codeministry.leadgen.config.ConfigFixtures;
import de.codeministry.leadgen.ingest.extract.TitleNormalizer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
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

/** Collapsing the listings of one project into one cluster. */
@SpringBootTest
@Testcontainers
class DeduplicationServiceTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    private static final String JAVA_LEAD = "Senior Java Entwickler (m/w/d)";

    @Autowired
    private DeduplicationService dedupe;

    @Autowired
    private JdbcTemplate jdbc;

    private long sourceId;

    @DynamicPropertySource
    static void configuration(DynamicPropertyRegistry registry) {
        registry.add("leadgen.config-dir", () -> shippedDefaults().toString());
    }

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM offer");
        jdbc.update("DELETE FROM source");
        sourceId = jdbc.queryForObject(
                "INSERT INTO source (name, kind) VALUES ('test', 'file') RETURNING id", Long.class);
    }

    @Test
    void mergesTwoListingsOfOneProjectAndKeepsEverySource() {
        // ISC-37. The same project reaches the aggregator through several portals under
        // different URLs, so the listings are distinct rows by construction — the upsert
        // cannot collapse them and is not supposed to.
        long first = insert(JAVA_LEAD, "FreelancerMap", "Etengo AG", minutesAgo(30));
        long second = insert(JAVA_LEAD, "freelance.de", "Hays AG", minutesAgo(20));

        assertThat(dedupe.run()).isEqualTo(1);

        assertThat(primaryOf(first)).isNull();
        assertThat(primaryOf(second)).isEqualTo(first);
        assertThat(portalsOfClusterLedBy(first)).containsExactlyInAnyOrder("FreelancerMap", "freelance.de");
    }

    @Test
    void keepsTheFirstSeenOfferAsPrimaryWhateverTheInsertOrder() {
        // ISC-39, the configured `keep_first_seen_as_primary`. Inserted newest first, so
        // a policy reading insertion order rather than `ingested_at` would pick wrongly.
        long newest = insert(JAVA_LEAD, "GULP", "GULP Solution Services", minutesAgo(5));
        long oldest = insert(JAVA_LEAD, "FreelancerMap", "Etengo AG", minutesAgo(90));

        dedupe.run();

        assertThat(primaryOf(oldest)).isNull();
        assertThat(primaryOf(newest)).isEqualTo(oldest);
    }

    @Test
    void attachesALateArrivalToTheClusterThatAlreadyExists() {
        // ISC-39 again, from the other side: a newsletter arrives daily, so the second
        // and third listing of a project turn up on later runs. Each must join the
        // primary that is already there rather than start a rival cluster.
        long first = insert(JAVA_LEAD, "FreelancerMap", "Etengo AG", minutesAgo(60));
        dedupe.run();

        long late = insert(JAVA_LEAD, "SOLCOM", "SOLCOM GmbH", minutesAgo(1));
        assertThat(dedupe.run()).isEqualTo(1);

        assertThat(primaryOf(first)).isNull();
        assertThat(primaryOf(late)).isEqualTo(first);
    }

    @Test
    void runningAgainChangesNothing() {
        // The pass runs after every ingest, and ingest runs daily. If it were not
        // idempotent the cluster would reshuffle on a schedule and nothing would say so.
        insert(JAVA_LEAD, "FreelancerMap", "Etengo AG", minutesAgo(60));
        insert(JAVA_LEAD, "freelance.de", "Hays AG", minutesAgo(30));

        dedupe.run();
        List<Long> after = assignments();

        assertThat(dedupe.run()).isEqualTo(1);
        assertThat(assignments()).isEqualTo(after);
    }

    @Test
    void collapsesEveryGroupToExactlyOneSurvivor() {
        // ISC-38's other half. The corpus assertion in SampleCorpusAcceptanceTest counts
        // fingerprint collisions — 159 of 1289 — without a database. This is the identity
        // that turns that count into a row count: n offers over k fingerprints leave
        // exactly n - k attached, whatever the group sizes are.
        insert(JAVA_LEAD, "A", "a", minutesAgo(50));
        insert(JAVA_LEAD, "B", "b", minutesAgo(40));
        insert(JAVA_LEAD, "C", "c", minutesAgo(30));
        insert("Angular Frontend Entwickler (w/m/d)", "A", "a", minutesAgo(20));
        insert("Angular Frontend Entwickler (w/m/d)", "B", "b", minutesAgo(10));
        insert("Kubernetes Platform Engineer (m/w/d)", "A", "a", minutesAgo(5));

        int offers = 6;
        int fingerprints = 3;
        assertThat(dedupe.run()).isEqualTo(offers - fingerprints);
    }

    @Test
    void mergesOnlyOnAnExactNormalizedTitle() {
        // ISC-40. Normalisation removes the gender suffix, casing and punctuation and
        // nothing else, so a title that merely resembles another stays its own offer.
        // The honest limit of a title-only fingerprint is the opposite case: two
        // genuinely different projects that happen to share a title do merge, and no
        // field available before enrichment separates them — the stated location was
        // measured and makes it worse.
        long lead = insert(JAVA_LEAD, "FreelancerMap", "Etengo AG", minutesAgo(60));
        long longer = insert("Senior Java Entwickler Spring Boot (m/w/d)", "freelance.de", "Hays AG", minutesAgo(50));
        long other = insert("Java Entwickler (m/w/d)", "GULP", "GULP Solution Services", minutesAgo(40));
        // Same project, three spellings the portals differ on. These do merge.
        long spelled = insert("senior java entwickler (w/m/d)", "SOLCOM", "SOLCOM GmbH", minutesAgo(30));

        assertThat(dedupe.run()).isEqualTo(1);

        assertThat(primaryOf(longer)).isNull();
        assertThat(primaryOf(other)).isNull();
        assertThat(primaryOf(spelled)).isEqualTo(lead);
    }

    private long insert(String title, String portal, String agency, Instant ingestedAt) {
        return jdbc.queryForObject(
                """
                INSERT INTO offer (source_id, external_id, title, url, portal, agency, fingerprint, ingested_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """,
                Long.class,
                sourceId,
                "ext-" + portal + "-" + title.hashCode(),
                title,
                "https://example.invalid/" + portal,
                portal,
                agency,
                TitleNormalizer.normalize(title),
                java.sql.Timestamp.from(ingestedAt));
    }

    private Long primaryOf(long id) {
        return jdbc.queryForObject("SELECT duplicate_of_id FROM offer WHERE id = ?", Long.class, id);
    }

    private List<String> portalsOfClusterLedBy(long primary) {
        return jdbc.queryForList(
                "SELECT portal FROM offer WHERE id = ? OR duplicate_of_id = ?", String.class, primary, primary);
    }

    private List<Long> assignments() {
        return jdbc.queryForList(
                "SELECT coalesce(duplicate_of_id, 0) FROM offer ORDER BY id", Long.class);
    }

    private static Instant minutesAgo(int minutes) {
        return Instant.now().minus(Duration.ofMinutes(minutes));
    }

    /** The shipped defaults, materialized: a broken default has to fail the build. */
    private static Path shippedDefaults() {
        try {
            Path dir = Files.createTempDirectory("leadgen-dedupe");
            dir.toFile().deleteOnExit();
            ConfigFixtures.materialize(dir);
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
