package de.codeministry.leadgen.score;

import static org.assertj.core.api.Assertions.assertThat;

import de.codeministry.leadgen.config.ConfigFixtures;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * ISC-50, and the state of a fresh clone on its first morning: the shipped configuration
 * carries no key, and the pipeline still has to produce a usable shortlist.
 */
@SpringBootTest
@Testcontainers
class ScoringWithoutAModelTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired
    private ScoringService scoring;

    @Autowired
    private JdbcTemplate jdbc;

    private long sourceId;

    @DynamicPropertySource
    static void configuration(DynamicPropertyRegistry registry) {
        registry.add("leadgen.config-dir", () -> shippedDefaults().toString());
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
    void completesAndLeavesTheOffersUnscored() {
        long id = offer("Senior Java Entwickler Spring Boot (m/w/d)", "Angular im Frontend, Kubernetes im Betrieb");

        var report = scoring.run();

        assertThat(report.considered()).isEqualTo(1);
        assertThat(report.unscored()).isEqualTo(1);
        assertThat(report.scored()).isZero();
        assertThat(jdbc.queryForObject("SELECT score_value FROM offer WHERE id = ?", Integer.class, id)).isNull();
        assertThat(jdbc.queryForObject("SELECT score_band FROM offer WHERE id = ?", String.class, id))
                .isEqualTo("UNSCORED");
        assertThat(jdbc.queryForObject("SELECT score_model FROM offer WHERE id = ?", String.class, id)).isNull();
    }

    @Test
    void keepsTheDeterministicReasonsAnyway() {
        // Unscored is not "nothing known". Everything the profile and the offer's own
        // fields can decide is decided and written; only the total is withheld, because
        // five of nine weights do not make a number comparable to one from all nine.
        long id = offer("Senior Java Entwickler (m/w/d)", "Spring Boot, Angular, Kubernetes, 12 Monate");

        scoring.run();

        var factors = jdbc.queryForList(
                "SELECT factor FROM offer_score_reason WHERE offer_id = ? ORDER BY position", String.class, id);
        assertThat(factors).contains("core_skill_overlap", "seniority_fit", "project_setup");
        assertThat(factors).doesNotContain("role_fit", "vague_description");
    }

    @Test
    void namesTheSkillsThatOverlappedAndCountsThemAgainstTheProfile() {
        // Against the shipped profile, which lists Java and Spring Boot. The label has to
        // name what actually matched: a number without a reason gets ignored within a
        // week, and "45 points" alone is such a number.
        long both = offer("Entwickler (m/w/d)", "Java 21 und Spring Boot, dazu Angular");
        long one = offer("Entwickler (m/w/d)", "Springboot und sonst nichts");

        scoring.run();

        assertThat(labelFor(both, "core_skill_overlap"))
                .contains("2 of 2")
                .contains("Java")
                .contains("Spring Boot");
        // An alias counts as the skill: an ad asking for "Springboot" is naming one.
        assertThat(labelFor(one, "core_skill_overlap")).contains("1 of 2").contains("Spring Boot");
        assertThat(pointsFor(both, "core_skill_overlap")).isGreaterThan(pointsFor(one, "core_skill_overlap"));
    }

    private String labelFor(long offerId, String factor) {
        return jdbc.queryForObject(
                "SELECT label FROM offer_score_reason WHERE offer_id = ? AND factor = ?",
                String.class, offerId, factor);
    }

    private int pointsFor(long offerId, String factor) {
        return jdbc.queryForObject(
                "SELECT points FROM offer_score_reason WHERE offer_id = ? AND factor = ?",
                Integer.class, offerId, factor);
    }

    @Test
    void saysSoWhenNoRateWasFoundAnywhere() {
        // The newsletter states a rate in 0.0 % of offers, so "no rate" is the normal
        // case and has to read as a fact rather than as a bad score.
        long id = offer("Senior Java Entwickler (m/w/d)", "Spring Boot");

        scoring.run();

        String label = jdbc.queryForObject(
                "SELECT label FROM offer_score_reason WHERE offer_id = ? AND factor = 'rate_fit'",
                String.class, id);
        assertThat(label).contains("no rate stated");
    }

    @Test
    void skipsAnOfferThatIsADuplicateOfAnother() {
        long primary = offer("Senior Java Entwickler (m/w/d)", "Spring Boot");
        long duplicate = offer("Senior Java Entwickler (m/w/d)", "Spring Boot");
        jdbc.update("UPDATE offer SET duplicate_of_id = ? WHERE id = ?", primary, duplicate);

        assertThat(scoring.run().considered()).isEqualTo(1);
    }

    private long offer(String title, String description) {
        return jdbc.queryForObject(
                """
                INSERT INTO offer (source_id, external_id, title, description, url, fingerprint, status)
                VALUES (?, ?, ?, ?, 'https://example.invalid/x', 'fp', 'PASSED')
                RETURNING id
                """,
                Long.class,
                sourceId,
                "ext-" + System.nanoTime(),
                title,
                description);
    }

    private static Path shippedDefaults() {
        try {
            Path dir = Files.createTempDirectory("leadgen-score-nomodel");
            dir.toFile().deleteOnExit();
            ConfigFixtures.materialize(dir);
            // The shipped file names its LLM settings as ${LLM_*} placeholders, and the
            // resolver reads the developer's own `.env` behind the process environment. So
            // a key on the machine running the build turned "no model configured" into a
            // real scoring run against a real endpoint, and the test that exists to prove
            // the keyless path failed for the one person who had finished configuring it.
            // The placeholders are emptied here: what is under test is the code path, not
            // whose machine it runs on.
            Path pipeline = dir.resolve("pipeline.yaml");
            Files.writeString(
                    pipeline,
                    Files.readString(pipeline).replaceAll("\\$\\{LLM_[A-Z_]+(?::[^}]*)?}", "''"));
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
