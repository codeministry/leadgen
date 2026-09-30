/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.chat.suggest;

import static org.assertj.core.api.Assertions.assertThat;

import de.codeministry.leadgen.Databases;
import de.codeministry.leadgen.config.ConfigFixtures;
import de.codeministry.leadgen.config.ConfigRegistry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
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
 * ISC-455: the suggestion service finds by rule, from the screens' read services, which questions
 * the data supports — one candidate per met trigger naming its count, none of the data ones from
 * an empty corpus, the two evergreen ones always, and a threshold moved through configuration
 * moving the set.
 */
@SpringBootTest
@Testcontainers
class ChatSuggestionServiceTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = Databases.postgres();

    static final Path CONFIG_DIRECTORY = freshConfigDirectory();

    private static final ZoneId ZONE = ZoneId.systemDefault();

    @Autowired
    private SuggestionService suggestions;

    @Autowired
    private ConfigRegistry config;

    @Autowired
    private JdbcTemplate jdbc;

    private long sourceId;

    @DynamicPropertySource
    static void configuration(DynamicPropertyRegistry registry) {
        registry.add("leadgen.config-dir", CONFIG_DIRECTORY::toString);
    }

    @BeforeEach
    void reset() throws IOException {
        jdbc.execute("TRUNCATE application_event, application, pipeline_run, offer, source RESTART IDENTITY CASCADE");
        sourceId =
                jdbc.queryForObject("INSERT INTO source (name, kind) VALUES ('test', 'file') RETURNING id", Long.class);
        ConfigFixtures.materialize(CONFIG_DIRECTORY);
        config.reload();
    }

    @Test
    void anEmptyCorpusYieldsOnlyTheTwoEvergreenCandidatesWithTheirNumbers() {
        var byTrigger = byTrigger(suggestions.candidates(SuggestionScope.NONE));

        assertThat(byTrigger).containsOnlyKeys(SuggestionService.SHORTLIST, SuggestionService.OPEN_APPLICATIONS);
        assertThat(byTrigger.get(SuggestionService.SHORTLIST).count()).isZero();
        assertThat(byTrigger.get(SuggestionService.OPEN_APPLICATIONS).count()).isZero();
        assertThat(byTrigger.values())
                .allSatisfy(candidate -> assertThat(candidate.sentenceKey()).startsWith("chat.suggest."));
    }

    @Test
    void aCorpusMeetingEachTriggerOnceYieldsOneCandidatePerTriggerNamingItsCount() {
        seed();

        var candidates = suggestions.candidates(new SuggestionScope(pinned(), List.of()));
        var byTrigger = byTrigger(candidates);

        assertThat(candidates).hasSize(7);
        assertThat(byTrigger)
                .containsOnlyKeys(
                        SuggestionService.NEW_OFFERS,
                        SuggestionService.DEADLINES,
                        SuggestionService.NO_REPLY,
                        SuggestionService.TAG_RISE,
                        SuggestionService.PINNED,
                        SuggestionService.SHORTLIST,
                        SuggestionService.OPEN_APPLICATIONS);
        assertThat(byTrigger.get(SuggestionService.NEW_OFFERS).count()).isEqualTo(4);
        assertThat(byTrigger.get(SuggestionService.DEADLINES).count()).isEqualTo(2);
        assertThat(byTrigger.get(SuggestionService.NO_REPLY).count()).isEqualTo(1);
        assertThat(byTrigger.get(SuggestionService.TAG_RISE).count()).isEqualTo(6);
        assertThat(byTrigger.get(SuggestionService.TAG_RISE).params()).containsEntry("tag", "kotlin");
        assertThat(byTrigger.get(SuggestionService.PINNED).count()).isEqualTo(1);
        // The shortlisted offers: the two with close deadlines and the far one.
        assertThat(byTrigger.get(SuggestionService.SHORTLIST).count()).isEqualTo(3);
        // Two SENT and one PACKAGED; the LOST one is closed.
        assertThat(byTrigger.get(SuggestionService.OPEN_APPLICATIONS).count()).isEqualTo(3);
    }

    @Test
    void aThresholdMovedThroughConfigurationMovesTheCandidateSet() {
        seed();
        var scope = new SuggestionScope(pinned(), List.of());

        suggestions("deadline_days", "25");
        assertThat(byTrigger(suggestions.candidates(scope))
                        .get(SuggestionService.DEADLINES)
                        .count())
                .isEqualTo(3);

        suggestions("deadline_days", "1");
        suggestions("no_reply_days", "30");
        suggestions("tag_rise_min_offers", "10");
        suggestions("new_offers_min", "5");
        var moved = byTrigger(suggestions.candidates(scope));

        assertThat(moved)
                .containsOnlyKeys(
                        SuggestionService.PINNED, SuggestionService.SHORTLIST, SuggestionService.OPEN_APPLICATIONS);
    }

    @Test
    void aTagRiseBelowThePercentageIsNoCandidate() {
        seed();

        suggestions("tag_rise_percent", "300");

        assertThat(byTrigger(suggestions.candidates(SuggestionScope.NONE)))
                .doesNotContainKey(SuggestionService.TAG_RISE);
    }

    /**
     * A last run that wrote four offers; two shortlisted offers with a deadline three days ahead
     * and one twenty days ahead; one application sent twenty days ago, one sent two days ago, one
     * packaged, one lost; six {@code kotlin} offers this week against two the week before, and
     * five {@code java} offers in each week.
     */
    private void seed() {
        Instant now = Instant.now();
        jdbc.update(
                """
                INSERT INTO pipeline_run (
                    started_at, finished_at, ruleset_version, score_model, status,
                    documents, extracted, written, merged,
                    filter_considered, filter_passed,
                    enrich_considered, enriched, incomplete, from_cache, requests,
                    score_considered, scored, unscored, shortlisted, review, submitted,
                    packaged, digest_written)
                VALUES (?, ?, '1', 'm', 'COMPLETE', 1, 4, 4, 0, 4, 3, 3, 3, 0, 0, 0, 3, 3, 0, 3, 0, 0, 0, true)
                """, Timestamp.from(now.minus(Duration.ofHours(2))), Timestamp.from(now.minus(Duration.ofHours(1))));

        LocalDate today = LocalDate.now(ZONE);
        long close1 = offer("close-1", "SHORTLISTED", today.plusDays(3), List.of(), now);
        long close2 = offer("close-2", "SHORTLISTED", today.plusDays(3), List.of(), now);
        long far = offer("far", "SHORTLISTED", today.plusDays(20), List.of(), now);
        long lost = offer("lost", "REVIEW", null, List.of(), now);
        offer("missed", "REVIEW", today.minusDays(2), List.of(), now);

        application(close1, "SENT", today.minusDays(20));
        application(close2, "SENT", today.minusDays(2));
        application(far, "PACKAGED", null);
        application(lost, "LOST", today.minusDays(40));

        for (int i = 0; i < 6; i++) {
            offer("kotlin-now-" + i, "REVIEW", null, List.of("kotlin"), now.minus(Duration.ofDays(1)));
        }
        for (int i = 0; i < 2; i++) {
            offer("kotlin-before-" + i, "REVIEW", null, List.of("kotlin"), now.minus(Duration.ofDays(10)));
        }
        for (int i = 0; i < 5; i++) {
            offer("java-now-" + i, "REVIEW", null, List.of("java"), now.minus(Duration.ofDays(2)));
            offer("java-before-" + i, "REVIEW", null, List.of("java"), now.minus(Duration.ofDays(9)));
        }
    }

    private long pinned() {
        return jdbc.queryForObject("SELECT min(id) FROM offer", Long.class);
    }

    private long offer(String key, String band, LocalDate applyBy, List<String> tags, Instant ingestedAt) {
        return jdbc.queryForObject(
                """
                INSERT INTO offer (source_id, external_id, title, url, fingerprint, status, score_band,
                                   score_value, apply_by, tags, ingested_at)
                VALUES (?, ?, ?, ?, ?, 'PASSED', ?, ?, ?, ?::text[], ?)
                RETURNING id
                """,
                Long.class,
                sourceId,
                key,
                "Offer " + key,
                "https://example.invalid/" + key,
                key,
                band,
                // The shortlist's band is read off the score against the configured thresholds,
                // not off score_band, so the score has to agree with the band.
                "SHORTLISTED".equals(band) ? 95 : 10,
                applyBy == null ? null : Date.valueOf(applyBy),
                "{" + String.join(",", tags) + "}",
                Timestamp.from(ingestedAt));
    }

    private void application(long offerId, String status, LocalDate sentOn) {
        jdbc.update(
                "INSERT INTO application (offer_id, status, sent_on) VALUES (?, ?, ?)",
                offerId,
                status,
                sentOn == null ? null : Date.valueOf(sentOn));
    }

    private static Map<String, SuggestionCandidate> byTrigger(List<SuggestionCandidate> candidates) {
        return candidates.stream().collect(Collectors.toMap(SuggestionCandidate::trigger, Function.identity()));
    }

    /** Sets one key of the {@code chat.suggestions} block in the materialised pipeline.yaml. */
    private void suggestions(String key, String value) {
        Path pipeline = CONFIG_DIRECTORY.resolve("pipeline.yaml");
        try {
            String text = Files.readString(pipeline, StandardCharsets.UTF_8);
            int block = text.indexOf("\n  suggestions:\n");
            if (block < 0) {
                throw new IllegalStateException("no `chat.suggestions:` in the shipped pipeline.yaml");
            }
            Matcher matcher = Pattern.compile("(?m)^([ \\t]*)" + Pattern.quote(key) + ":.*$")
                    .matcher(text);
            if (!matcher.find(block)) {
                throw new IllegalStateException("no `" + key + ":` under chat.suggestions");
            }
            text = text.substring(0, matcher.start())
                    + matcher.group(1) + key + ": " + value
                    + text.substring(matcher.end());
            Files.writeString(pipeline, text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        config.reload();
    }

    private static Path freshConfigDirectory() {
        try {
            Path dir = Files.createTempDirectory("leadgen-chat-suggest");
            dir.toFile().deleteOnExit();
            ConfigFixtures.materialize(dir);
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
