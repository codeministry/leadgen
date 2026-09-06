/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.score;

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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

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
        jdbc.update("DELETE FROM score_batch");
        jdbc.update("DELETE FROM source");
        sourceId =
                jdbc.queryForObject("INSERT INTO source (name, kind) VALUES ('test', 'file') RETURNING id", Long.class);
    }

    @Test
    void completesAndLeavesTheOffersUnscored() {
        long id = offer("Senior Java Entwickler Spring Boot (m/w/d)", "Angular im Frontend, Kubernetes im Betrieb");

        var report = scoring.run();

        assertThat(report.considered()).isEqualTo(1);
        assertThat(report.unscored()).isEqualTo(1);
        assertThat(report.scored()).isZero();
        assertThat(jdbc.queryForObject("SELECT score_value FROM offer WHERE id = ?", Integer.class, id))
                .isNull();
        assertThat(jdbc.queryForObject("SELECT score_band FROM offer WHERE id = ?", String.class, id))
                .isEqualTo("UNSCORED");
        assertThat(jdbc.queryForObject("SELECT score_model FROM offer WHERE id = ?", String.class, id))
                .isNull();
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
        assertThat(factors).contains("core_skill_overlap", "seniority_fit");
        assertThat(factors).doesNotContain("role_fit", "vague_description");
    }

    @Test
    void writesNoReasonForSomethingTheOfferNeverStated() {
        // The difference this whole scale rests on. "12 Monate" in the prose is not a
        // stated duration — the column is what enrichment fills, and it is empty here — so
        // the factor has nothing to say and stays out of the total entirely. Scored as a
        // zero instead, it cost every offer alike: 101 of 101 carried a 0-point `rate_fit`
        // row for a rate the source states in 0.0 % of offers, and the highest score in the
        // table was 53.
        long id = offer("Senior Java Entwickler (m/w/d)", "Spring Boot, 12 Monate");

        scoring.run();

        var factors = jdbc.queryForList(
                "SELECT factor FROM offer_score_reason WHERE offer_id = ? ORDER BY position", String.class, id);
        assertThat(factors).doesNotContain("rate_fit", "project_setup", "industry_fit");
    }

    @Test
    void scoresAStatedRateBelowTheFloorAsZeroRatherThanAsSilence() {
        // The other half of the same rule: a rate that is stated and is bad is a judgement
        // about the offer, so it keeps its zero and its place in the denominator.
        long id = offer("Senior Java Entwickler (m/w/d)", "Spring Boot");
        jdbc.update("UPDATE offer SET rate_eur = 35 WHERE id = ?", id);

        scoring.run();

        assertThat(pointsFor(id, "rate_fit")).isZero();
        assertThat(labelFor(id, "rate_fit")).contains("below the floor");
    }

    @Test
    void addsTheStatedShapeOfTheEngagementRatherThanChargingForIt() {
        // A bonus, not a share. Inside the denominator an ad naming one of three fields
        // scores 3 of 10 and comes out below one that names nothing at all, because saying
        // nothing keeps the factor out of the denominator entirely.
        long bare = offer("Senior Java Entwickler (m/w/d)", "Spring Boot");
        long stated = offer("Senior Java Entwickler (m/w/d)", "Spring Boot");
        jdbc.update("UPDATE offer SET duration = '12', starts_on = DATE '2026-11-01' WHERE id = ?", stated);

        scoring.run();

        var bareFactors =
                jdbc.queryForList("SELECT factor FROM offer_score_reason WHERE offer_id = ?", String.class, bare);
        assertThat(bareFactors).doesNotContain("project_setup");
        assertThat(pointsFor(stated, "project_setup")).isPositive();
    }

    @Test
    void namesTheSkillsThatOverlappedAndCountsThemAgainstTheProfile() {
        // Against the shipped profile, which lists Java and Spring Boot. The label has to
        // name what actually matched: a number without a reason gets ignored within a
        // week, and "45 points" alone is such a number.
        long both = offer("Entwickler (m/w/d)", "Java 21 und Spring Boot, dazu Angular");
        long one = offer("Entwickler (m/w/d)", "Springboot und sonst nichts");

        scoring.run();

        // The weight, not the count: the shipped profile weights both core skills at 10 and
        // saturates at the two of them, so naming both is the full 20.
        assertThat(labelFor(both, "core_skill_overlap"))
                .contains("skill weight 20, a full match is 20")
                .contains("Java")
                .contains("Spring Boot");
        // An alias counts as the skill: an ad asking for "Springboot" is naming one.
        assertThat(labelFor(one, "core_skill_overlap"))
                .contains("skill weight 10, a full match is 20")
                .contains("Spring Boot");
        assertThat(pointsFor(both, "core_skill_overlap")).isGreaterThan(pointsFor(one, "core_skill_overlap"));
    }

    private String labelFor(long offerId, String factor) {
        return jdbc.queryForObject(
                "SELECT label FROM offer_score_reason WHERE offer_id = ? AND factor = ?",
                String.class,
                offerId,
                factor);
    }

    private int pointsFor(long offerId, String factor) {
        return jdbc.queryForObject(
                "SELECT points FROM offer_score_reason WHERE offer_id = ? AND factor = ?",
                Integer.class,
                offerId,
                factor);
    }

    /**
     * The three tests below are about what a run <i>re-does</i>, not about the keyless path.
     * They live here because the guard is model-independent and this class already owns a
     * container and a configuration with no key: a judge would add nothing to what they
     * assert, and a second Postgres would cost every build a container for three cases.
     */
    @Test
    void writesNothingASecondTimeWhenNothingHasChanged() {
        // The whole point of the guard. Every stage before this one already works this way;
        // scoring did not, so each run re-judged the standing backlog and paid for it. The
        // observable is the timestamp: an offer that is not due is not written at all.
        long id = offer("Senior Java Entwickler (m/w/d)", "Spring Boot, Angular");
        scoring.run();
        var first = scoredAt(id);

        var second = scoring.run();

        assertThat(scoredAt(id)).isEqualTo(first);
        // And the report still describes the shortlist rather than the idle pass, or a
        // quiet run would read as scoring having stopped working.
        assertThat(second.considered()).isEqualTo(1);
        assertThat(second.unscored()).isEqualTo(1);
    }

    @Test
    void leavesAnOfferAloneWhileItIsWaitingInASubmittedBatch() {
        // The pointer has to beat every staleness trigger, not sit beside them. Its whole
        // job is that an answer already bought and on its way is not bought again, and the
        // way that fails is silently: two batches, two bills, one score.
        long id = offer("Senior Java Entwickler (m/w/d)", "Spring Boot");
        scoring.run();
        var first = scoredAt(id);
        long batch = jdbc.queryForObject(
                """
                        INSERT INTO score_batch (provider_id, model, ruleset_version, offers)
                        VALUES ('msgbatch_test', 'some-model', 'an older ruleset', 1)
                        RETURNING id
                        """,
                Long.class);
        // Stale by the ruleset as well, so what is being tested is the pointer and not the
        // absence of a reason to look again.
        jdbc.update(
                "UPDATE offer SET score_batch_id = ?, ruleset_version = 'an older ruleset' WHERE id = ?", batch, id);

        scoring.run();

        assertThat(scoredAt(id)).isEqualTo(first);
    }

    @Test
    void scoresAgainWhenTheWeightsThatProducedTheScoreHaveChanged() {
        long id = offer("Senior Java Entwickler (m/w/d)", "Spring Boot");
        scoring.run();
        var first = scoredAt(id);
        jdbc.update("UPDATE offer SET ruleset_version = 'an older ruleset' WHERE id = ?", id);

        scoring.run();

        assertThat(scoredAt(id)).isAfter(first);
    }

    @Test
    void scoresAgainWhenADifferentModelWroteTheLastScore() {
        // Two judges are two scales, and the shortlist threshold is one number read
        // against both. Switching the model has to re-score rather than leave a mixed list.
        long id = offer("Senior Java Entwickler (m/w/d)", "Spring Boot");
        scoring.run();
        var first = scoredAt(id);
        jdbc.update("UPDATE offer SET score_model = 'whoever answered yesterday' WHERE id = ?", id);

        scoring.run();

        assertThat(scoredAt(id)).isAfter(first);
        assertThat(jdbc.queryForObject("SELECT score_model FROM offer WHERE id = ?", String.class, id))
                .isNull();
    }

    @Test
    void refusesToScoreOneOfferAgainWhenNothingIsConfiguredToAnswer() {
        // The button on the detail page is the deliberate exception to the guard, so its
        // failure mode matters: rewriting the deterministic half and calling it done would
        // look exactly like the button having no effect.
        long id = offer("Senior Java Entwickler (m/w/d)", "Spring Boot");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> scoring.rescore(id))
                .isInstanceOf(ScoringService.NoJudge.class)
                .hasMessageContaining("no language model is configured");
    }

    /**
     * `timestamptz` does not convert straight to an Instant; the driver throws on the whole query.
     */
    private java.sql.Timestamp scoredAt(long offerId) {
        return jdbc.queryForObject("SELECT scored_at FROM offer WHERE id = ?", java.sql.Timestamp.class, offerId);
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
            Files.writeString(pipeline, Files.readString(pipeline).replaceAll("\\$\\{LLM_[A-Z_]+(?::[^}]*)?}", "''"));
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
