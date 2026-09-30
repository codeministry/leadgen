/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import de.codeministry.leadgen.Databases;
import de.codeministry.leadgen.application.ApplicationEvent;
import de.codeministry.leadgen.application.ApplicationStatus;
import de.codeministry.leadgen.chat.tools.ApplicationResult;
import de.codeministry.leadgen.chat.tools.ApplicationTool;
import de.codeministry.leadgen.chat.tools.OfferHit;
import de.codeministry.leadgen.chat.tools.OfferSearchResult;
import de.codeministry.leadgen.chat.tools.OfferSearchTool;
import de.codeministry.leadgen.chat.tools.ProfileTool;
import de.codeministry.leadgen.chat.tools.SemanticSearchTool;
import de.codeministry.leadgen.chat.tools.StatisticsTool;
import de.codeministry.leadgen.config.ConfigFixtures;
import de.codeministry.leadgen.config.model.SkillProfile;
import de.codeministry.leadgen.llm.Vectors;
import de.codeministry.leadgen.retrieval.QueryEmbedder;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The chat's tools against the screens they must not disagree with.
 *
 * <p>Each test is the probe of one claim of spec {@code 019-corpus-chat}. The comparison is
 * always with the endpoint the screen calls, over HTTP, and never with the service behind it:
 * a tool that called the service with a different default than the controller does would agree
 * with the service and still name a list the shortlist does not show.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ChatToolsTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = Databases.postgres();

    private static final ObjectMapper JSON =
            new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    /** The retrieval model this class's configuration names, which the vector column is read with. */
    private static final String EMBEDDING_MODEL = "test-embed";

    private static final Path CONFIG = chatConfig();

    /**
     * The shipped files with two edits: retrieval switched on over a test embedding model, and
     * the skill profile's reference project renamed, so the profile tool can only return the
     * override by reading the layer in force. The thresholds stay shipped, so the review band is
     * 50 to 69 on every machine.
     */
    @DynamicPropertySource
    static void configuration(DynamicPropertyRegistry registry) {
        registry.add("leadgen.config-dir", CONFIG::toString);
    }

    /** The query phrase's vector, decided by the test rather than by a model. */
    @Autowired
    private de.codeministry.leadgen.llm.LlmBudget llmBudget;

    @Autowired
    private de.codeministry.leadgen.chat.ChatBudget chatBudget;

    @MockitoBean
    private QueryEmbedder queries;

    @Autowired
    private SemanticSearchTool semantic;

    @Autowired
    private StatisticsTool statistics;

    @Autowired
    private ApplicationTool applications;

    @Autowired
    private ProfileTool profile;

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private OfferSearchTool search;

    private long sourceId;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM offer_score_reason");
        jdbc.update("DELETE FROM offer");
        jdbc.update("DELETE FROM source");
        sourceId =
                jdbc.queryForObject("INSERT INTO source (name, kind) VALUES ('test', 'file') RETURNING id", Long.class);
        // Forty survivors spread over every axis the three filter sets read: scores with gaps
        // and ties, three portals, stated and unstated start, length and deadline, and ingest
        // timestamps shared by three rows at a time, so the order is decided by the tie-break
        // as often as by the key. More than the tool's page match the text search, so the cap
        // is inside the comparison rather than beside it.
        for (int i = 0; i < 40; i++) {
            long id = passed(i);
            if (i % 3 == 1) {
                topicReason(id, "Spring Boot");
            }
        }
        // Rows neither side may show: a knocked-out offer and an attached duplicate.
        jdbc.update("""
            INSERT INTO offer (source_id, external_id, title, url, fingerprint, status, filter_stage)
            VALUES (?, 'rejected', 'Java Developer rejected', 'https://example.invalid/r', 'rejected',
                    'REJECTED', 'ABROAD')
            """, sourceId);
        long primary = jdbc.queryForObject("SELECT min(id) FROM offer WHERE status = 'PASSED'", Long.class);
        jdbc.update("""
            INSERT INTO offer (source_id, external_id, title, url, fingerprint, status, portal,
                               duplicate_of_id)
            VALUES (?, 'dup', 'Java Developer 0', 'https://portal-d/x', 'java developer 0 dup', 'PASSED',
                    'portal-d', ?)
            """, sourceId, primary);
    }

    /**
     * ISC-425: the offer search tool takes the shortlist's own filters and returns the same ids
     * in the same order as the shortlist endpoint for the same filters.
     */
    @Test
    void searchesOffersInTheShortlistsOwnOrderForThreeFilterSets() throws Exception {
        // Text over the whole advert, newest first — and more matches than one page.
        assertSameList(
                search.searchOffers("java", null, null, null, null, null, null, null, null, "fresh", null, null),
                "/api/v1/offers?q=java&sort=fresh");
        // A band, two portals, only open deadlines, soonest deadline first.
        assertSameList(
                search.searchOffers(
                        null,
                        "review",
                        null,
                        null,
                        List.of("portal-b", "portal-c"),
                        null,
                        null,
                        true,
                        null,
                        "deadline",
                        null,
                        null),
                "/api/v1/offers?band=review&portal=portal-b&portal=portal-c&deadlineOpen=true&sort=deadline");
        // A profile topic, a start window and a minimum length, longest first. The space is
        // written as a space: MockMvcTester encodes the template, so a pre-encoded `%20` would
        // reach the controller as the literal `%20` and the topic would match nothing.
        assertSameList(
                search.searchOffers(
                        null, null, null, null, null, "later", 3, null, "Spring Boot", "duration", null, null),
                "/api/v1/offers?topic=Spring Boot&startWindow=later&minMonths=3&sort=duration");
    }

    /**
     * ISC-443: with a came-in window the search returns exactly the shortlist's ids for the same
     * filters whose offer came in inside the window, in the shortlist's order, with {@code
     * matched} counted over the window; without one it is the shortlist unchanged.
     *
     * <p>Thirty offers in the middle month, more than the tool's page, so a window applied to one
     * page after the fact would both drop rows and miscount; only a count over the window meets
     * this. The September month is the forty rows every test seeds.
     */
    @Test
    void windowsTheSearchToTheMonthTheOffersCameIn() throws Exception {
        for (int i = 0; i < 6; i++) {
            cameIn("Java July " + i, 40 + i * 9, "2026-07-" + (10 + i) + "T12:00:00Z");
        }
        for (int i = 0; i < 30; i++) {
            cameIn("Java August " + i, 20 + (i * 13) % 80, "2026-08-" + (10 + i % 15) + "T12:00:00Z");
        }
        ZoneId zone = ZoneId.systemDefault();
        Instant after = LocalDate.parse("2026-08-01").atStartOfDay(zone).toInstant();
        Instant before = LocalDate.parse("2026-09-01").atStartOfDay(zone).toInstant();

        JsonNode whole = get("/api/v1/offers?q=java&sort=score&limit=200");
        assertThat(whole.get("matched").asInt())
                .as("the whole match fits one request")
                .isLessThan(200);
        List<Long> inAugust = new ArrayList<>();
        whole.get("entries").forEach(entry -> {
            Instant at = Instant.parse(entry.get("offer").get("ingestedAt").asText());
            if (!at.isBefore(after) && at.isBefore(before)) {
                inAugust.add(entry.get("offer").get("id").asLong());
            }
        });
        assertThat(inAugust).hasSize(30);

        OfferSearchResult windowed = search.searchOffers(
                "java", null, null, null, null, null, null, null, null, "score", "2026-08-01", "2026-09-01");
        assertThat(windowed.offers().stream().map(OfferHit::id).toList())
                .containsExactlyElementsOf(inAugust.subList(0, OfferSearchTool.PAGE));
        assertThat(windowed.matched()).isEqualTo(inAugust.size());

        assertSameList(
                search.searchOffers("java", null, null, null, null, null, null, null, null, "score", null, null),
                "/api/v1/offers?q=java&sort=score");
    }

    /**
     * ISC-424: the semantic tool searches the vectors over the working set and the archive,
     * flags the archived hit, and never returns a knocked-out offer, an attached duplicate or an
     * offer without a vector, the first two seeded nearer the query than either primary.
     */
    @Test
    void searchesByMeaningOverTheWorkingSetAndTheArchive() {
        var take = org.mockito.ArgumentCaptor.forClass(java.util.function.BooleanSupplier.class);
        when(queries.vectorFor(
                        org.mockito.ArgumentMatchers.eq("event streaming"),
                        org.mockito.ArgumentMatchers.eq(EMBEDDING_MODEL),
                        take.capture()))
                .thenReturn(Optional.of(Vectors.literal(direction(0))));
        long working = vectored("Kafka platform", "PASSED", null, direction(2));
        long archived = vectored("Kafka archive", "PASSED", null, direction(3));
        jdbc.update("UPDATE offer SET archived_at = now() WHERE id = ?", archived);
        long knockedOut = vectored("Kafka abroad", "REJECTED", null, direction(1));
        long duplicate = vectored("Kafka platform", "PASSED", working, direction(1));
        long unread = vectored("Kafka unread", "PASSED", null, null);

        var result = semantic.searchByMeaning("event streaming");

        assertThat(result.offers()).extracting(OfferHit::id).containsExactlyInAnyOrder(working, archived);
        assertThat(result.offers()).extracting(OfferHit::id).doesNotContain(knockedOut, duplicate, unread);
        assertThat(result.offers())
                .filteredOn(hit -> hit.id() == archived)
                .singleElement()
                .satisfies(hit -> assertThat(hit.archived()).isTrue());
        assertThat(result.offers())
                .filteredOn(hit -> hit.id() == working)
                .singleElement()
                .satisfies(hit -> assertThat(hit.archived()).isFalse());

        // ISC-433: the embedding request is paid from the chat's own day, never from `llm.budget`.
        int pipelineBefore = llmBudget.used();
        int chatBefore = chatBudget.used();
        assertThat(take.getValue().getAsBoolean()).isTrue();
        assertThat(chatBudget.used()).isEqualTo(chatBefore + 1);
        assertThat(llmBudget.used()).isEqualTo(pipelineBefore);
    }

    /**
     * ISC-433 for the topic filter: {@code search_offers} with a topic embeds the topic's name for
     * its paraphrase half, and that request is paid from the chat's day, never from
     * {@code llm.budget}. Both embedder entry points are stubbed to pay what they would pay, so the
     * path the tool takes shows on the counters.
     */
    @Test
    void aTopicSearchPaysItsEmbeddingFromTheChatsBudget() {
        String vector = Vectors.literal(direction(0));
        when(queries.vectorFor(
                        org.mockito.ArgumentMatchers.eq("Spring Boot"),
                        org.mockito.ArgumentMatchers.eq(EMBEDDING_MODEL)))
                .thenAnswer(call -> {
                    llmBudget.take();
                    return Optional.of(vector);
                });
        when(queries.vectorFor(
                        org.mockito.ArgumentMatchers.eq("Spring Boot"),
                        org.mockito.ArgumentMatchers.eq(EMBEDDING_MODEL),
                        org.mockito.ArgumentMatchers.any(java.util.function.BooleanSupplier.class)))
                .thenAnswer(call -> {
                    call.<java.util.function.BooleanSupplier>getArgument(2).getAsBoolean();
                    return Optional.of(vector);
                });
        int pipelineBefore = llmBudget.used();
        int chatBefore = chatBudget.used();

        OfferSearchResult result =
                search.searchOffers(null, null, null, null, null, null, null, null, "Spring Boot", null, null, null);

        assertThat(result.matched()).isPositive();
        assertThat(chatBudget.used()).isEqualTo(chatBefore + 1);
        assertThat(llmBudget.used()).isEqualTo(pipelineBefore);
    }

    /**
     * ISC-426: the statistics tool answers with the numbers the dashboard and analytics endpoints
     * answer with, for the analytics window and for a week inside it.
     */
    @Test
    void answersStatisticsWithTheScreensOwnNumbers() throws Exception {
        jdbc.update("""
            UPDATE offer SET ingested_at = now() - make_interval(days => (id % 6)::int),
                             score_band = CASE WHEN score_value >= 70 THEN 'SHORTLISTED'
                                               WHEN score_value >= 50 THEN 'REVIEW'
                                               WHEN score_value IS NULL THEN NULL ELSE 'DISCARDED' END
            WHERE id % 2 = 0
            """);
        long offer = jdbc.queryForObject("SELECT min(id) FROM offer WHERE status = 'PASSED'", Long.class);
        long application = jdbc.queryForObject(
                "INSERT INTO application (offer_id, status, sent_on) VALUES (?, 'SENT', current_date) RETURNING id",
                Long.class,
                offer);
        jdbc.update(
                "INSERT INTO application_event (application_id, from_status, to_status) VALUES (?, 'PACKAGED', 'SENT')",
                application);

        JsonNode analytics = get("/api/v1/analytics");
        JsonNode summary = get("/api/v1/analytics/summary");
        var last = mvc.get().uri("/api/v1/ingest/last").exchange();

        var whole = statistics.statistics(null, null, null, null);
        assertThat(tree(whole.funnel())).isEqualTo(analytics.get("funnel"));
        assertThat(tree(whole.intake())).isEqualTo(analytics.get("intake").get("byIngestedAt"));
        assertThat(tree(whole.runs())).isEqualTo(analytics.get("runs").get("days"));
        assertThat(tree(whole.scores())).isEqualTo(analytics.get("scores"));
        assertThat(tree(whole.applications()))
                .isEqualTo(analytics.get("applications").get("byStatus"));
        assertThat(tree(whole.responses()))
                .isEqualTo(analytics.get("applications").get("response"));
        assertThat(tree(whole.scoreBands())).isEqualTo(summary.get("scoreBands"));
        assertThat(tree(whole.lastRunHealth())).isEqualTo(summary.get("lastRun"));
        if (last.getResponse().getStatus() == 200) {
            JsonNode run = JSON.readTree(last.getResponse().getContentAsString(StandardCharsets.UTF_8));
            assertThat(whole.lastRun().extracted())
                    .isEqualTo(run.get("extracted").asInt());
            assertThat(whole.lastRun().scored()).isEqualTo(run.get("scored").asInt());
        } else {
            assertThat(whole.lastRun()).isNull();
        }

        LocalDate to = LocalDate.parse(analytics.get("to").asText());
        LocalDate from = to.minusDays(6);
        var week = statistics.statistics(from.toString(), to.toString(), null, null);
        List<JsonNode> expected = new ArrayList<>();
        analytics.get("intake").get("byIngestedAt").forEach(day -> {
            LocalDate on = LocalDate.parse(day.get("day").asText());
            if (!on.isBefore(from) && !on.isAfter(to)) {
                expected.add(day);
            }
        });
        assertThat(expected).isNotEmpty();
        assertThat(tree(week.intake())).isEqualTo(JSON.valueToTree(expected));
        assertThat(week.from()).isEqualTo(from);
        assertThat(week.to()).isEqualTo(to);
    }

    /**
     * ISC-458: the statistics tool carries the analytics screen's market section (portals, tags,
     * locations, reach), its stage mix and its scales, each equal to {@code GET /api/v1/analytics};
     * inside a window the stage mix loses the days outside it, the way the screen's daily series do,
     * and the states stay whole.
     */
    @Test
    void answersTheMarketTheStageMixAndTheScalesOfTheAnalyticsScreen() throws Exception {
        jdbc.update("""
            UPDATE offer SET ingested_at = now() - make_interval(days => (id % 9)::int),
                             tags = CASE id % 3 WHEN 0 THEN ARRAY['java', 'spring']
                                                WHEN 1 THEN ARRAY['java'] ELSE ARRAY['kotlin'] END,
                             location = CASE WHEN id % 2 = 0 THEN 'Köln' ELSE 'Remote' END,
                             score_model = CASE WHEN score_value IS NULL THEN NULL ELSE 'judge-a' END,
                             ruleset_version = CASE WHEN score_value IS NULL THEN NULL
                                                    WHEN id % 4 = 0 THEN 'rules-1' ELSE 'rules-2' END,
                             scored_at = CASE WHEN score_value IS NULL THEN NULL
                                              ELSE now() - make_interval(hours => id::int) END
            """);
        // Knockouts spread over twelve days, so the stage mix has days inside a week and outside it.
        jdbc.update("""
            INSERT INTO offer (source_id, external_id, title, url, fingerprint, status, filter_stage,
                               portal, location, tags, ingested_at)
            SELECT ?, 'knocked-' || g, 'Knocked out ' || g, 'https://example.invalid/k' || g, 'knocked-' || g,
                   'REJECTED', CASE WHEN g % 2 = 0 THEN 'ABROAD' ELSE 'OUT_OF_REACH' END,
                   'portal-' || substr('abc', g % 3 + 1, 1), 'Zürich', ARRAY['java'],
                   now() - make_interval(days => g)
              FROM generate_series(0, 11) AS g
            """, sourceId);

        JsonNode analytics = get("/api/v1/analytics");
        JsonNode market = analytics.get("market");
        // Guards the guard: every compared list holds something, so no comparison is vacuous.
        for (String part : List.of("portals", "tags", "locations", "stageMix")) {
            assertThat(market.get(part)).as(part).isNotEmpty();
        }
        assertThat(analytics.get("scales")).hasSizeGreaterThanOrEqualTo(2);

        var whole = statistics.statistics(null, null, null, null);
        assertThat(tree(whole.market().portals())).isEqualTo(market.get("portals"));
        assertThat(tree(whole.market().tags())).isEqualTo(market.get("tags"));
        assertThat(tree(whole.market().locations())).isEqualTo(market.get("locations"));
        assertThat(tree(whole.market().reach())).isEqualTo(market.get("reach"));
        assertThat(tree(whole.market().stageMix())).isEqualTo(market.get("stageMix"));
        assertThat(tree(whole.scales())).isEqualTo(analytics.get("scales"));

        LocalDate to = LocalDate.parse(analytics.get("to").asText());
        LocalDate from = to.minusDays(4);
        var window = statistics.statistics(from.toString(), to.toString(), null, null);
        List<JsonNode> stages = new ArrayList<>();
        market.get("stageMix").forEach(day -> {
            LocalDate on = LocalDate.parse(day.get("day").asText());
            if (!on.isBefore(from) && !on.isAfter(to)) {
                stages.add(day);
            }
        });
        assertThat(stages).isNotEmpty().hasSizeLessThan(market.get("stageMix").size());
        assertThat(tree(window.market().stageMix())).isEqualTo(JSON.valueToTree(stages));
        assertThat(tree(window.market().portals())).isEqualTo(market.get("portals"));
        assertThat(tree(window.market().tags())).isEqualTo(market.get("tags"));
        assertThat(tree(window.market().locations())).isEqualTo(market.get("locations"));
        assertThat(tree(window.market().reach())).isEqualTo(market.get("reach"));
        assertThat(tree(window.scales())).isEqualTo(analytics.get("scales"));
    }

    /**
     * ISC-459: with a comparison window the statistics tool returns both windows' numbers, each
     * equal to {@code GET /api/v1/analytics} cut to its own window, and the differences computed on
     * the server, each equal to the endpoint's two numbers subtracted. The corpus spans three months,
     * so both thirty-day windows hold days and the two totals differ.
     */
    @Test
    void comparesTwoWindowsWithTheScreensOwnNumbersAndTheirDifferences() throws Exception {
        jdbc.update("""
            UPDATE offer SET ingested_at = now() - make_interval(days => ((id * 7) % 90)::int),
                             score_band = CASE WHEN score_value >= 70 THEN 'SHORTLISTED'
                                               WHEN score_value >= 50 THEN 'REVIEW'
                                               WHEN score_value IS NULL THEN NULL ELSE 'DISCARDED' END
            """);
        jdbc.update("""
            INSERT INTO offer (source_id, external_id, title, url, fingerprint, status, filter_stage,
                               portal, ingested_at)
            SELECT ?, 'gone-' || g, 'Knocked out ' || g, 'https://example.invalid/g' || g, 'gone-' || g,
                   'REJECTED', CASE WHEN g % 2 = 0 THEN 'ABROAD' ELSE 'OUT_OF_REACH' END, 'portal-a',
                   now() - make_interval(days => (g * 5) % 90)
              FROM generate_series(0, 23) AS g
            """, sourceId);

        JsonNode analytics = get("/api/v1/analytics");
        LocalDate to = LocalDate.parse(analytics.get("to").asText());
        LocalDate from = to.minusDays(29);
        LocalDate compareTo = from.minusDays(1);
        LocalDate compareFrom = compareTo.minusDays(29);

        var result =
                statistics.statistics(from.toString(), to.toString(), compareFrom.toString(), compareTo.toString());
        var comparison = result.comparison();
        assertThat(comparison).as("the comparison block").isNotNull();
        assertThat(comparison.from()).isEqualTo(compareFrom);
        assertThat(comparison.to()).isEqualTo(compareTo);
        assertThat(comparison.comparison()).isNull();

        // Each window's series and totals, against the endpoint cut to that window.
        JsonNode main = expectedTotals(analytics, from, to);
        JsonNode other = expectedTotals(analytics, compareFrom, compareTo);
        assertThat(main.get("primaries").asInt()).isPositive();
        assertThat(other.get("primaries").asInt()).isPositive();
        assertThat(main).isNotEqualTo(other);
        assertThat(tree(result.intake())).isEqualTo(days(analytics.get("intake").get("byIngestedAt"), from, to));
        assertThat(tree(comparison.intake()))
                .isEqualTo(days(analytics.get("intake").get("byIngestedAt"), compareFrom, compareTo));
        assertThat(tree(result.runs())).isEqualTo(days(analytics.get("runs").get("days"), from, to));
        assertThat(tree(comparison.runs())).isEqualTo(days(analytics.get("runs").get("days"), compareFrom, compareTo));
        assertThat(tree(result.market().stageMix()))
                .isEqualTo(days(analytics.get("market").get("stageMix"), from, to));
        assertThat(tree(comparison.market().stageMix()))
                .isEqualTo(days(analytics.get("market").get("stageMix"), compareFrom, compareTo));
        assertThat(tree(result.totals())).isEqualTo(main);
        assertThat(tree(comparison.totals())).isEqualTo(other);
        // The states are whole in both blocks, the screen's own.
        assertThat(tree(comparison.funnel())).isEqualTo(analytics.get("funnel"));
        assertThat(tree(comparison.applications()))
                .isEqualTo(analytics.get("applications").get("byStatus"));

        // Each difference is the endpoint's two numbers subtracted, this window minus the other.
        var differences = JSON.createObjectNode();
        main.properties()
                .forEach(entry -> differences.put(
                        entry.getKey(),
                        entry.getValue().asInt() - other.get(entry.getKey()).asInt()));
        assertThat(tree(result.differences())).isEqualTo(differences);
        assertThat(comparison.differences()).isNull();

        // Without a comparison window there is no second block and nothing to subtract.
        var alone = statistics.statistics(from.toString(), to.toString(), null, null);
        assertThat(alone.comparison()).isNull();
        assertThat(alone.differences()).isNull();
        assertThat(tree(alone.totals())).isEqualTo(main);
    }

    /** The endpoint's days of one series inside a window, as the endpoint wrote them. */
    private static JsonNode days(JsonNode series, LocalDate from, LocalDate to) {
        List<JsonNode> kept = new ArrayList<>();
        series.forEach(day -> {
            LocalDate on = LocalDate.parse(day.get("day").asText());
            if (!on.isBefore(from) && !on.isAfter(to)) {
                kept.add(day);
            }
        });
        return JSON.valueToTree(kept);
    }

    /** The window's totals, summed here from the endpoint's own days. */
    private static JsonNode expectedTotals(JsonNode analytics, LocalDate from, LocalDate to) {
        var totals = JSON.createObjectNode();
        JsonNode intake = days(analytics.get("intake").get("byIngestedAt"), from, to);
        for (String field :
                List.of("primaries", "duplicates", "passed", "shortlisted", "review", "discarded", "unscored")) {
            int sum = 0;
            for (JsonNode day : intake) {
                sum += day.get(field).asInt();
            }
            totals.put(field, sum);
        }
        int runs = 0;
        for (JsonNode day : days(analytics.get("runs").get("days"), from, to)) {
            runs += day.get("runs").asInt();
        }
        totals.put("runs", runs);
        int knockouts = 0;
        for (JsonNode day : days(analytics.get("market").get("stageMix"), from, to)) {
            knockouts += day.get("removed").asInt();
        }
        totals.put("knockouts", knockouts);
        return totals;
    }

    /**
     * ISC-427: the application tool returns the state, the event log and the letter; the profile
     * tool returns the reference projects of the layer in force, which here is the override in
     * the configuration directory and not the shipped example.
     */
    @Test
    void returnsTheApplicationWithItsLogAndLetterAndTheProfileInForce() throws Exception {
        long offer = jdbc.queryForObject("SELECT min(id) FROM offer WHERE status = 'PASSED'", Long.class);
        jdbc.update("""
            UPDATE offer SET package_dir = 'offer-letter', packaged_at = now(),
                             cover_letter_text = 'Guten Tag, anbei meine Unterlagen.',
                             cover_letter_author = 'edited', cover_letter_at = now()
            WHERE id = ?
            """, offer);
        long application = jdbc.queryForObject(
                "INSERT INTO application (offer_id, status) VALUES (?, 'SENT') RETURNING id", Long.class, offer);
        jdbc.update("""
            INSERT INTO application_event (application_id, from_status, to_status, recorded_at) VALUES
              (?, NULL, 'SHORTLISTED', now() - interval '3 days'),
              (?, 'SHORTLISTED', 'PACKAGED', now() - interval '2 days'),
              (?, 'PACKAGED', 'SENT', now() - interval '1 day')
            """, application, application, application);

        var found = applications.application(offer);

        assertThat(found.status()).isEqualTo(ApplicationStatus.SENT);
        assertThat(found.applicationId()).isEqualTo(application);
        assertThat(found.events())
                .extracting(ApplicationEvent::toStatus)
                .containsExactly(ApplicationStatus.SENT, ApplicationStatus.PACKAGED, ApplicationStatus.SHORTLISTED);
        JsonNode letter = get("/api/v1/offers/" + offer + "/cover-letter");
        assertThat(found.coverLetter())
                .isEqualTo(letter.get("text").asText())
                .isEqualTo("Guten Tag, anbei meine Unterlagen.");

        assertThat(profile.profile().referenceProjects())
                .extracting(SkillProfile.ReferenceProject::id)
                .containsExactly("override-project");
    }

    /**
     * Finding 6: an archived offer's application is still that offer's application. The board
     * leaves archived offers out, so a tool that scanned it answered "no application" for exactly
     * the ones that were sent and are done with.
     */
    @Test
    void returnsTheApplicationOfAnArchivedOffer() {
        long offer = jdbc.queryForObject("SELECT max(id) FROM offer WHERE status = 'PASSED'", Long.class);
        jdbc.update("UPDATE offer SET archived_at = now() WHERE id = ?", offer);
        long application = jdbc.queryForObject(
                "INSERT INTO application (offer_id, status, sent_on) VALUES (?, 'SENT', current_date) RETURNING id",
                Long.class,
                offer);

        ApplicationResult[] found = new ApplicationResult[1];
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> found[0] = applications.application(offer)))
                .as("the application of an archived offer")
                .isNull();
        assertThat(found[0].applicationId()).isEqualTo(application);
        assertThat(found[0].status()).isEqualTo(ApplicationStatus.SENT);
    }

    private JsonNode get(String uri) throws Exception {
        var result = mvc.get().uri(uri).exchange();
        assertThat(result.getResponse().getStatus()).as(uri).isEqualTo(200);
        return JSON.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static JsonNode tree(Object value) {
        return JSON.valueToTree(value);
    }

    private void cameIn(String title, int score, String at) {
        jdbc.update(
                """
                INSERT INTO offer (source_id, external_id, title, description, url, fingerprint, status,
                                   score_value, portal, ingested_at)
                VALUES (?, ?, ?, 'Replacing a monolith.', ?, ?, 'PASSED', ?, 'portal-a', CAST(? AS timestamptz))
                """,
                sourceId,
                "ext-" + title,
                title,
                "https://example.invalid/" + title.replace(' ', '-'),
                title.toLowerCase(),
                score,
                at);
    }

    private long vectored(String title, String status, Long duplicateOf, float[] vector) {
        long id = jdbc.queryForObject(
                """
                INSERT INTO offer (source_id, external_id, title, url, fingerprint, status, filter_stage,
                                   score_value, portal, duplicate_of_id)
                VALUES (?, ?, ?, ?, ?, ?, CASE WHEN ? = 'REJECTED' THEN 'ABROAD' END, 60, 'portal-a', ?)
                RETURNING id
                """,
                Long.class,
                sourceId,
                "vec-" + title + "-" + status + "-" + duplicateOf,
                title,
                "https://example.invalid/vec/" + title.replace(' ', '-') + "-" + status + "-" + duplicateOf,
                title.toLowerCase() + " " + status + " " + duplicateOf,
                status,
                status,
                duplicateOf);
        if (vector != null) {
            jdbc.update(
                    "UPDATE offer SET retrieval_embedding = CAST(? AS vector), retrieval_embedding_model = ?,"
                            + " retrieval_embedded_at = now() WHERE id = ?",
                    Vectors.literal(vector),
                    EMBEDDING_MODEL,
                    id);
        }
        return id;
    }

    /** A unit vector in the first plane, {@code step} two-thousandths of a quarter turn from the axis. */
    private static float[] direction(int step) {
        float[] vector = new float[Vectors.DIMENSIONS];
        double angle = step * Math.PI / 2000;
        vector[0] = (float) Math.cos(angle);
        vector[1] = (float) Math.sin(angle);
        return vector;
    }

    private static Path chatConfig() {
        try {
            Path dir = Files.createTempDirectory("leadgen-chat-tools");
            dir.toFile().deleteOnExit();
            ConfigFixtures.materialize(dir);
            Path pipeline = dir.resolve("pipeline.yaml");
            String text = Files.readString(pipeline, StandardCharsets.UTF_8);
            text = set(text, "provider", "openai-compatible");
            text = set(text, "base_url", "http://localhost:1/v1");
            text = set(text, "api_key", "test-key");
            text = set(text, "embedding", EMBEDDING_MODEL);
            // By its placeholder: `enabled:` appears on several blocks in this file.
            text = text.replace("${RETRIEVAL_ENABLED:false}", "true");
            // A floor, so a topic filter asks for its paraphrase half and the embedder is reached.
            text = text.replace("${RETRIEVAL_TOPIC_FLOOR:}", "0.5");
            Files.writeString(pipeline, text, StandardCharsets.UTF_8);

            Path profile = dir.resolve("skill-profile.yaml");
            String shipped = Files.readString(profile, StandardCharsets.UTF_8);
            String override = shipped.replace("id: example-project", "id: override-project")
                    .replace("title_en: Example project", "title_en: Override project");
            if (override.equals(shipped)) {
                throw new IllegalStateException("the shipped profile no longer names example-project");
            }
            Files.writeString(profile, override, StandardCharsets.UTF_8);
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

    /**
     * The endpoint is asked for the tool's page size, so the two lists are the same page of the
     * same query; the count is compared too, because it is what keeps a capped page from being
     * read as the whole match.
     */
    private void assertSameList(OfferSearchResult tool, String uri) throws Exception {
        var result = mvc.get().uri(uri + "&limit=" + OfferSearchTool.PAGE).exchange();
        assertThat(result.getResponse().getStatus()).as(uri).isEqualTo(200);
        JsonNode page = JSON.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        List<Long> shortlist = new ArrayList<>();
        page.get("entries")
                .forEach(entry -> shortlist.add(entry.get("offer").get("id").asLong()));

        assertThat(shortlist)
                .as("the shortlist must show something for %s", uri)
                .isNotEmpty();
        assertThat(tool.offers().stream().map(OfferHit::id).toList()).as(uri).containsExactlyElementsOf(shortlist);
        assertThat(tool.matched()).as(uri).isEqualTo(page.get("matched").asInt());
        assertThat(tool.offers())
                .as(uri)
                .allSatisfy(hit -> assertThat(hit.archived()).isFalse());
    }

    private long passed(int i) {
        String title = (i % 5 == 4 ? "Angular Frontend " : "Java Developer ") + i;
        return jdbc.queryForObject(
                """
                INSERT INTO offer (source_id, external_id, title, description, url, fingerprint, status,
                                   score_value, portal, tags, starts_on, duration_months, apply_by, ingested_at)
                VALUES (?, ?, ?, 'Replacing a monolith.', ?, ?, 'PASSED', ?, ?, ARRAY['Java'],
                        CASE WHEN ? THEN NULL ELSE current_date + ? END,
                        ?,
                        CASE WHEN ? THEN NULL ELSE current_date + ? END,
                        timestamptz '2026-09-01 08:00:00+00' + make_interval(hours => ?))
                RETURNING id
                """,
                Long.class,
                sourceId,
                "ext-" + i,
                title,
                "https://example.invalid/" + i,
                title.toLowerCase(),
                i % 6 == 0 ? null : 30 + (i * 7) % 70,
                "portal-" + "abc".charAt(i % 3),
                i % 7 == 0,
                i * 3 - 30,
                i % 4 == 0 ? null : (i % 12) + 1,
                i % 5 == 0,
                i % 9 - 4,
                i / 3);
    }

    private void topicReason(long offerId, String topic) {
        jdbc.update(
                "INSERT INTO offer_score_reason (offer_id, factor, label, points, max_points, topic, position)"
                        + " VALUES (?, 'interest_fit', ?, 8, 0, ?, 0)",
                offerId,
                "interest: " + topic,
                topic);
    }
}
