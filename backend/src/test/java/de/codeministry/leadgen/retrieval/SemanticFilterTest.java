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
import de.codeministry.leadgen.llm.Vectors;
import de.codeministry.leadgen.offer.OfferQueryService;
import de.codeministry.leadgen.offer.RelatedFilter;
import de.codeministry.leadgen.offer.ShortlistQuery;
import de.codeministry.leadgen.offer.ShortlistSort;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
 * The relatedness filter on the read side: what it narrows, and what it must leave alone.
 *
 * <p>Every case here uses {@code similar=}, which costs no model call — both vectors are in the
 * table — so this needs no stubbed endpoint to prove the half that matters. The vectors are
 * written by hand, because what is under test is the SQL and the shape, not the model.
 */
@SpringBootTest
@Testcontainers
class SemanticFilterTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = Databases.postgres();

    /** What `retrieval.neighbours` is set to in this class's configuration. */
    private static final int NEIGHBOURS = 2;

    private static final Path CONFIG = retrievalOn();

    @Autowired
    private OfferQueryService offers;

    @Autowired
    private SemanticFilter filter;

    /** The topic phrase's vector, decided by the test rather than by a model. */
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private QueryEmbedder queries;

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
    void findsAParaphraseWithinTheTopicFloorBesideTheStoredAliasMatches() {
        org.mockito.Mockito.when(queries.vectorFor("Wanted topic", "test-embed"))
                .thenReturn(java.util.Optional.of(Vectors.literal(direction(0))));
        long named = offer("Nennt den Alias", 40, direction(900));
        jdbc.update(
                "INSERT INTO offer_score_reason (offer_id, factor, label, points, max_points, topic, position)"
                        + " VALUES (?, 'interest_fit', 'interest: Wanted topic', 12, 0, 'Wanted topic', 0)",
                named);
        long paraphrase = offer("Umschreibt das Thema", 20, direction(1));
        long unrelated = offer("Etwas anderes", 90, direction(900));

        var page = offers.shortlist(ShortlistQuery.first().withTopic("Wanted topic"));

        assertThat(ids(page)).containsExactlyInAnyOrder(named, paraphrase);
        assertThat(ids(page)).doesNotContain(unrelated);
    }

    @Test
    void answersATopicWithItsAliasMatchesAloneWhenThePhraseCannotBeEmbedded() {
        // A spent budget or an unreachable model: the paraphrase half is absent, the rest stands.
        org.mockito.Mockito.when(queries.vectorFor("Wanted topic", "test-embed"))
                .thenReturn(java.util.Optional.empty());
        long named = offer("Nennt den Alias", 40, direction(900));
        jdbc.update(
                "INSERT INTO offer_score_reason (offer_id, factor, label, points, max_points, topic, position)"
                        + " VALUES (?, 'interest_fit', 'interest: Wanted topic', 12, 0, 'Wanted topic', 0)",
                named);
        offer("Umschreibt das Thema", 20, direction(1));

        assertThat(ids(offers.shortlist(ShortlistQuery.first().withTopic("Wanted topic"))))
                .containsExactly(named);
    }

    @Test
    void narrowsTheSetAndLeavesTheOrderAlone() {
        // The whole design in one assertion. The nearest neighbour of the anchor is the
        // lowest-scoring of the three, and the list still comes back by score — because this
        // is a filter and the first row is not the best match.
        long anchor = offer("Anker", 50, direction(0));
        long near = offer("Nah", 10, direction(1));
        long far = offer("Fern", 90, direction(900));

        var page = offers.shortlist(related(anchor));

        assertThat(ids(page)).containsExactly(anchor, near);
        assertThat(ids(page)).doesNotContain(far);
        // Score descending, which is what was asked for: the anchor at 50 before the near one
        // at 10, and not the near one first because it is nearer.
        assertThat(page.entries().getFirst().offer().id()).isEqualTo(anchor);
    }

    @Test
    void keepsTheAnchorInItsOwnResult() {
        // An offer's distance to itself is zero, so it is always in its own neighbourhood.
        // That is what keeps it visible in the list the reader was looking at when they asked,
        // and why the screen needs no "back to the offer" affordance of its own.
        long anchor = offer("Anker", 50, direction(0));
        offer("Nah", 10, direction(1));

        assertThat(ids(offers.shortlist(related(anchor)))).contains(anchor);
    }

    @Test
    void holdsToTheConfiguredCount() {
        long anchor = offer("Anker", 90, direction(0));
        for (int index = 1; index <= 5; index++) {
            offer("Nachbar " + index, 80 - index, direction(index));
        }

        // Six offers, all indexed, and the page could hold fifty: what bounds this is
        // `retrieval.neighbours` and nothing else.
        assertThat(offers.shortlist(related(anchor)).entries()).hasSize(NEIGHBOURS);
    }

    @Test
    void dropsRowsWithNoVectorRatherThanSortingThemToTheEnd() {
        // A filter removes; it does not rank. An offer with no vector is not "far away", it is
        // outside the question — which is the honest reading while the backfill is still
        // running and most of the archive has no vector at all.
        long anchor = offer("Anker", 50, direction(0));
        long unindexed = offer("Ohne Vektor", 99, null);

        assertThat(ids(offers.shortlist(related(anchor)))).containsExactly(anchor);
        assertThat(ids(offers.shortlist(related(anchor)))).doesNotContain(unindexed);
    }

    @Test
    void ignoresAVectorFromAnotherModel() {
        // Two models are two spaces. A row from another one is not far away, it is not
        // comparable — and a cosine against it is a number rather than an error.
        long anchor = offer("Anker", 50, direction(0));
        long other = offer("Anderes Modell", 60, direction(1));
        jdbc.update("UPDATE offer SET retrieval_embedding_model = 'a-different-model' WHERE id = ?", other);

        assertThat(ids(offers.shortlist(related(anchor)))).containsExactly(anchor);
    }

    @Test
    void countsTheMatchOverTheNarrowedSetAndTheTotalOverTheWorkingList() {
        // "2 of 4" has to keep meaning what it says: the match is what the filters left, the
        // total is what they were applied to. The neighbourhood bounds the first and not the
        // second, which is what the sentence beside the list depends on.
        long anchor = offer("Anker", 50, direction(0));
        offer("Nah", 40, direction(1));
        offer("Fern", 30, direction(500));
        offer("Weiter weg", 20, direction(900));

        var page = offers.shortlist(related(anchor));

        assertThat(page.matched()).isEqualTo(2);
        assertThat(page.total()).isEqualTo(4);
    }

    @Test
    void walksTwoPagesWithoutRepeatingOrSkippingWhileTheFilterIsOn() {
        // The reason this is a filter and not a seventh sort: the keyset cursor keeps working
        // untouched, because the narrowing does not redefine the key.
        long anchor = offer("Anker", 100, direction(0));
        for (int index = 1; index <= 4; index++) {
            offer("Nachbar " + index, 90 - index, direction(index));
        }

        // A neighbourhood of two, walked one row at a time.
        var first = offers.shortlist(related(anchor).withLimit(1));
        var second = offers.shortlist(related(anchor).withLimit(1).withCursor(first.nextCursor()));

        assertThat(first.entries()).hasSize(1);
        assertThat(second.entries()).hasSize(1);
        assertThat(ids(first)).doesNotContainAnyElementsOf(ids(second));
    }

    @Test
    void survivesEveryOtherSortWithTheFilterOn() {
        // The cursor's key changes with the sort and the narrowing does not touch it, so every
        // order has to keep working. Exhaustive on purpose: a seventh sort added later fails
        // here rather than at a page boundary on a Tuesday.
        long anchor = offer("Anker", 50, direction(0));
        offer("Nah", 40, direction(1));

        for (ShortlistSort sort : ShortlistSort.values()) {
            assertThat(offers.shortlist(related(anchor).withSort(sort)).entries())
                    .describedAs("sort=%s", sort.key())
                    .hasSize(2);
        }
    }

    @Test
    void namesTheAnchorSoTheChipCanSaySoWithoutASecondRequest() {
        long anchor = offer("Senior Java Entwickler (m/w/d)", 50, direction(0));

        assertThat(offers.shortlist(related(anchor)).relatedTo()).isEqualTo("Senior Java Entwickler (m/w/d)");
        assertThat(offers.shortlist(ShortlistQuery.first()).relatedTo()).isNull();
    }

    @Test
    void countsHowMuchOfTheWorkingListTheFilterCanReach() {
        // The number behind the sentence that stops a short result reading as a quiet market
        // while the backfill is still running.
        offer("Mit Vektor", 50, direction(0));
        offer("Ohne Vektor", 40, null);

        var coverage = filter.coverage();

        assertThat(coverage.readable()).isEqualTo(1);
        assertThat(coverage.total()).isEqualTo(2);
    }

    @Test
    void refusesAnAnchorThatHasNoVectorRatherThanAnsweringWithArbitraryOffers() {
        // The bug this guard exists for, and it is the quiet kind. A scalar subselect for an
        // offer with no vector is NULL, `<=>` against NULL is NULL for every row, and
        // `ORDER BY NULL ... LIMIT k` hands back k arbitrary offers — which the screen would
        // then present as the ones related to this one. Found by wiring the detail's button,
        // not by a failing test.
        long unindexed = offer("Ohne Vektor", 50, null);
        offer("Irgendwas", 40, direction(1));
        offer("Irgendwas anderes", 30, direction(2));

        assertThatThrownBy(() -> offers.shortlist(related(unindexed)))
                .isInstanceOf(SemanticFilter.RetrievalUnavailable.class)
                .hasMessageContaining("has not been read for meaning yet");
    }

    @Test
    void refusesBothSpellingsOfOneNarrowingAtOnce() {
        assertThatThrownBy(() -> new RelatedFilter("kubernetes", 7L))
                .hasMessageContaining("two spellings of one filter");
    }

    private List<Long> ids(de.codeministry.leadgen.offer.ShortlistPage page) {
        return page.entries().stream().map(entry -> entry.offer().id()).toList();
    }

    /**
     * The neighbourhood around one offer, at the default page size.
     *
     * <p>The page and the neighbourhood are two different limits and an earlier version of this
     * helper conflated them: {@code withLimit} bounds the page, {@code retrieval.neighbours}
     * bounds the set. Passing the wanted neighbourhood as a page size makes every assertion below
     * a statement about paging that happens to pass.
     */
    private ShortlistQuery related(long anchor) {
        return ShortlistQuery.first().withRelated(new RelatedFilter(null, anchor));
    }

    /**
     * A unit vector pointing a little further round the circle for each step, so "nearest" is
     * something the test decides rather than something the model decides.
     */
    private static float[] direction(int step) {
        float[] vector = new float[Vectors.DIMENSIONS];
        double angle = step * Math.PI / 2000;
        vector[0] = (float) Math.cos(angle);
        vector[1] = (float) Math.sin(angle);
        return vector;
    }

    private long offer(String title, int score, float[] vector) {
        long id = jdbc.queryForObject(
                """
                INSERT INTO offer (source_id, external_id, title, description, url, location, portal,
                                   fingerprint, status, score_value, score_band)
                VALUES (?, ?, ?, 'Teaser.', ?, 'Köln', 'portal-a', ?, 'PASSED', ?, 'REVIEW')
                RETURNING id
                """,
                Long.class,
                sourceId,
                "ext-" + title.hashCode() + "-" + Math.random(),
                title,
                "https://example.invalid/" + Math.random(),
                TitleNormalizer.normalize(title),
                score);
        if (vector != null) {
            jdbc.update(
                    "UPDATE offer SET retrieval_embedding = CAST(? AS vector),"
                            + " retrieval_embedding_model = 'test-embed', retrieval_embedded_at = now() WHERE id = ?",
                    Vectors.literal(vector),
                    id);
        }
        return id;
    }

    /** The shipped defaults with the stage switched on and an embedding model named. */
    private static Path retrievalOn() {
        try {
            Path dir = Files.createTempDirectory("leadgen-semantic");
            dir.toFile().deleteOnExit();
            ConfigFixtures.materialize(dir);
            Path pipeline = dir.resolve("pipeline.yaml");
            String text = Files.readString(pipeline, StandardCharsets.UTF_8);
            text = set(text, "provider", "openai-compatible");
            text = set(text, "base_url", "http://localhost:1/v1");
            text = set(text, "api_key", "test-key");
            text = set(text, "embedding", "test-embed");
            // By its placeholder: `enabled:` appears on five blocks in this file.
            text = text.replace("${RETRIEVAL_ENABLED:false}", "true");
            // Two, so the neighbourhood is something these tests can reason about. The shipped
            // 200 would put every fixture row inside it and turn every assertion below into a
            // statement about the page size instead.
            text = text.replace("neighbours: 200", "neighbours: " + NEIGHBOURS);
            // A floor these fixtures can reason about: direction(1) is at cosine ~1 to direction(0),
            // direction(900) at ~0.16. The real number is measured, not chosen like this one.
            text = text.replace("${RETRIEVAL_TOPIC_FLOOR:}", "0.9");
            Files.writeString(pipeline, text, StandardCharsets.UTF_8);
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String set(String yaml, String key, String value) {
        Matcher matcher =
                Pattern.compile("(?m)^([ \\t]*)" + Pattern.quote(key) + ":.*$").matcher(yaml);
        if (!matcher.find()) {
            throw new IllegalStateException("no `" + key + ":` in the shipped pipeline.yaml");
        }
        return matcher.replaceFirst("$1" + key + ": " + Matcher.quoteReplacement(value));
    }
}
