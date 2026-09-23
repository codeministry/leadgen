/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.codeministry.leadgen.Databases;
import de.codeministry.leadgen.config.ConfigFixtures;
import de.codeministry.leadgen.ingest.extract.TitleNormalizer;
import de.codeministry.leadgen.offer.OfferQueryService;
import de.codeministry.leadgen.offer.RelatedFilter;
import de.codeministry.leadgen.offer.ShortlistQuery;
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
 * The shared link that arrives on an installation without the index.
 *
 * <p>This is the dark twin of "the control is simply absent", and the case that decides whether
 * the whole read path is honest. A saved view or a link somebody sent carries
 * {@code ?semantic=kubernetes}, and here nothing can answer it.
 *
 * <p><b>The parameter must not be ignored.</b> Dropped, the request returns the unfiltered list
 * under a heading that says it was narrowed, and the count beside it is true about a set the
 * reader never asked for — which is exactly the class of quiet wrongness this repository keeps
 * paying for. So it is a 400 with a sentence, and the browser renders that in place of the list
 * while the chip stays removable.
 */
@SpringBootTest
@Testcontainers
class SemanticSearchWithoutRetrievalTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = Databases.postgres();

    private static final Path CONFIG = shippedDefaults();

    @Autowired
    private OfferQueryService offers;

    @Autowired
    private SemanticFilter filter;

    @Autowired
    private JdbcTemplate jdbc;

    private long sourceId;

    @DynamicPropertySource
    static void configuration(DynamicPropertyRegistry registry) {
        registry.add("leadgen.config-dir", () -> CONFIG.toString());
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
    void refusesAPhraseRatherThanWideningTheListInSilence() {
        offer("Senior Java Entwickler (m/w/d)");
        offer("Angular Entwickler (m/w/d)");

        assertThatThrownBy(() ->
                        offers.shortlist(ShortlistQuery.first().withRelated(new RelatedFilter("kubernetes", null))))
                .isInstanceOf(SemanticFilter.RetrievalUnavailable.class)
                .hasMessageContaining("does not search by meaning");
    }

    @Test
    void refusesAnAnchorTheSameWay() {
        long offer = offer("Senior Java Entwickler (m/w/d)");

        assertThatThrownBy(() -> offers.shortlist(ShortlistQuery.first().withRelated(new RelatedFilter(null, offer))))
                .isInstanceOf(SemanticFilter.RetrievalUnavailable.class);
    }

    @Test
    void saysTheCapabilityIsAbsentSoTheScreenCanLeaveTheControlOut() {
        // `related == null` on the envelope is the whole flag. A disabled control would be a
        // second thing to explain; an absent one explains itself.
        offer("Senior Java Entwickler (m/w/d)");

        assertThat(filter.available()).isFalse();
        assertThat(filter.coverage()).isNull();
        assertThat(offers.shortlist(ShortlistQuery.first()).related()).isNull();
    }

    @Test
    void answersATopicWithTheAliasMatchesRatherThanRefusingIt() {
        // Unlike `semantic=`, a topic has an answer that needs no index: the scorer already
        // stored which offers name it. Without an embedder the paraphrase half is absent and
        // the alias half is the whole answer, so this is a list and not a 400.
        long named = offer("Senior Java Entwickler (m/w/d)");
        offer("Angular Entwickler (m/w/d)");
        jdbc.update(
                "INSERT INTO offer_score_reason (offer_id, factor, label, points, max_points, topic, position)"
                        + " VALUES (?, 'interest_fit', 'interest: Wanted topic', 12, 0, 'Wanted topic', 0)",
                named);

        var page = offers.shortlist(ShortlistQuery.first().withTopic("Wanted topic"));

        assertThat(page.entries()).extracting(entry -> entry.offer().id()).containsExactly(named);
        assertThat(page.matched()).isEqualTo(1);
    }

    @Test
    void leavesEveryOtherFilterWorking() {
        // What is lost is the semantic search and nothing else, which is the whole point of
        // narrowing rather than ranking: an absent filter leaves a working screen behind it.
        offer("Senior Java Entwickler (m/w/d)");
        offer("Angular Entwickler (m/w/d)");

        var page = offers.shortlist(ShortlistQuery.first());

        assertThat(page.entries()).hasSize(2);
        assertThat(page.matched()).isEqualTo(2);
    }

    private long offer(String title) {
        return jdbc.queryForObject(
                """
                INSERT INTO offer (source_id, external_id, title, description, url, location, portal,
                                   fingerprint, status)
                VALUES (?, ?, ?, 'Teaser.', ?, 'Köln', 'portal-a', ?, 'PASSED')
                RETURNING id
                """,
                Long.class,
                sourceId,
                "ext-" + title.hashCode(),
                title,
                "https://example.invalid/" + title.hashCode(),
                TitleNormalizer.normalize(title));
    }

    /**
     * The shipped defaults exactly as they ship — {@code retrieval.enabled} off — with the
     * {@code ${LLM_*}} placeholders emptied so the developer's own `.env` cannot turn this into
     * a run against a real endpoint. {@code ScoringWithoutAModelTest} learned that one the hard
     * way.
     */
    private static Path shippedDefaults() {
        try {
            Path dir = Files.createTempDirectory("leadgen-search-nothing");
            dir.toFile().deleteOnExit();
            ConfigFixtures.materialize(dir);
            Path pipeline = dir.resolve("pipeline.yaml");
            Files.writeString(pipeline, Files.readString(pipeline).replaceAll("\\$\\{LLM_[A-Z_]+(?::[^}]*)?}", "''"));
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
