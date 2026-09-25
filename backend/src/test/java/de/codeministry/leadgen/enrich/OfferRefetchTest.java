/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.enrich;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.extension.ResponseDefinitionTransformerV2;
import com.github.tomakehurst.wiremock.http.ResponseDefinition;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import de.codeministry.leadgen.Databases;
import de.codeministry.leadgen.config.ConfigFixtures;
import de.codeministry.leadgen.config.ConfigRegistry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The button that fetches one original ad again, through the endpoint, against a stubbed
 * portal, a stubbed model and a real database.
 *
 * <p>Each test is the probe of one claim of spec {@code 004-refetch-original-ad}, which is
 * why it goes through HTTP rather than calling the service: the claims are about what the
 * endpoint does to the cache, the window and the offer row.
 *
 * <p>The model is configured, and named rather than left to the placeholders: the classifier,
 * the field extractor and the judge all read {@code llm.models.scoring}, and a successful
 * fetch runs all three. Left as {@code ${LLM_*}}, the resolver would fill them from the
 * developer's {@code .env} and this suite would call a real model. The keyless half of the
 * spec is {@link OfferRefetchWithoutAModelTest}, because a context has one configuration.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class OfferRefetchTest {

    /**
     * Started in a static initialiser, before {@link #CONFIG} is built: the configuration
     * names its URL, and static fields are initialised in textual order.
     */
    private static final WireMockServer MODEL;

    static {
        MODEL = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        MODEL.start();
    }

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = Databases.postgres();

    /**
     * What the portal does while it is still composing an answer, for the one stub that asks for
     * it by name. It runs before the response leaves, which a request listener does not promise,
     * so a probe can put something in the database between the lookup and the write.
     */
    private static volatile Runnable whileAnswering = () -> {};

    private static final WireMockServer PORTAL =
            new WireMockServer(WireMockConfiguration.options().dynamicPort().extensions(new WhileAnswering()));

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * The fetch timeout this class runs under, shortened from the shipped ten seconds so the
     * timeout probe costs three short waits (one attempt, two retries) rather than thirty
     * seconds. The portal's delay in that probe is twice this.
     */
    private static final int FETCH_TIMEOUT_MILLIS = 1000;

    /** Created once: a {@code @DynamicPropertySource} supplier may run more than once. */
    private static final Path CONFIG = configPointingAtTheStub();

    /**
     * One phrase from each stage's system prompt. The three stages ask the same model at the
     * same endpoint and expect three different answers, so the stub routes by which question
     * it was asked.
     */
    private static final String CLASSIFIER = "You are cleaning up job adverts";

    private static final String EXTRACTOR = "pulling three facts out of it";
    private static final String JUDGE = "You assess freelance project offers";

    private static final String AD_HTML = """
        <html><body>
          <h1>Senior Java Entwickler (m/w/d)</h1>
          <article>
            Für ein Logistikunternehmen suchen wir Verstärkung.
            Stundensatz 95 EUR/h, Laufzeit 12 Monate, 4 Tage / Woche,
            80 % remote, Start ab 01.10.2026.
          </article>
        </body></html>
        """;

    /** Read fine, and the rate is on it, but nothing the `full_text` selector would take. */
    private static final String PAGE_WITHOUT_AD_TEXT = """
        <html><body>
          <h1>Senior Java Entwickler (m/w/d)</h1>
          <div class="teaser">Stundensatz 95 EUR/h. Details nach Login.</div>
        </body></html>
        """;

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private FetchWindow window;

    @Autowired
    private ConfigRegistry config;

    @Autowired
    private JdbcTemplate jdbc;

    private long sourceId;

    @BeforeAll
    static void startPortal() {
        PORTAL.start();
        WireMock.configureFor("localhost", PORTAL.port());
    }

    @AfterAll
    static void stopServers() {
        PORTAL.stop();
        MODEL.stop();
    }

    @DynamicPropertySource
    static void configuration(DynamicPropertyRegistry registry) {
        registry.add("leadgen.config-dir", CONFIG::toString);
    }

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM offer_score_reason");
        jdbc.update("DELETE FROM offer");
        jdbc.update("DELETE FROM fetched_page");
        jdbc.update("DELETE FROM source");
        sourceId =
                jdbc.queryForObject("INSERT INTO source (name, kind) VALUES ('test', 'file') RETURNING id", Long.class);
        PORTAL.resetAll();
        MODEL.resetAll();
        window.clear();
        whileAnswering = () -> {};
        PORTAL.stubFor(get(urlEqualTo("/robots.txt")).willReturn(aResponse().withStatus(404)));
        modelAnswersEveryStage();
    }

    @Test
    void fetchesPastACachedFailureAndRemembersTheNewAnswer() {
        // ISC-244. The night stamped this offer and cached the 403 for a week, so a run would
        // read the refusal back without asking. The page answers now, and the button asks it.
        String path = "/projekt/was-forbidden";
        long id = unfetchedOffer(path);
        jdbc.update(
                "INSERT INTO fetched_page (url, status, body, fetched_at) VALUES (?, 403, NULL, now())",
                PORTAL.baseUrl() + path);
        stubFor(get(urlEqualTo(path)).willReturn(aResponse().withBody(AD_HTML)));

        assertThat(mvc.post().uri("/api/v1/offers/{id}/fetch", id)).hasStatusOk();

        verify(1, getRequestedFor(urlEqualTo(path)));
        assertThat(jdbc.queryForObject(
                        "SELECT status FROM fetched_page WHERE url = ?", Integer.class, PORTAL.baseUrl() + path))
                .isEqualTo(200);
    }

    @Test
    void neverAsksForAPathRobotsTxtDisallows() {
        // ISC-245, the robots half. The button goes past the cache, and past nothing else: a
        // disallowed path is refused before a request is made, and the refusal is recorded on
        // the offer like any other outcome rather than answered as an error.
        String path = "/projekt/private";
        long id = unfetchedOffer(path);
        PORTAL.stubFor(get(urlEqualTo("/robots.txt"))
                .willReturn(aResponse().withBody("User-agent: *\nDisallow: /projekt/private\n")));
        stubFor(get(urlEqualTo(path)).willReturn(aResponse().withBody(AD_HTML)));

        assertThat(mvc.post().uri("/api/v1/offers/{id}/fetch", id)).hasStatusOk();

        verify(1, getRequestedFor(urlEqualTo("/robots.txt")));
        verify(0, getRequestedFor(urlEqualTo(path)));
        assertThat(jdbc.queryForObject("SELECT enrichment_note FROM offer WHERE id = ?", String.class, id))
                .contains("robots.txt");
        assertThat(jdbc.queryForObject(
                        "SELECT status FROM fetched_page WHERE url = ?", Integer.class, PORTAL.baseUrl() + path))
                .isZero();
    }

    @Test
    void answers429WithoutAnyRequestWhenARunHasSpentTheWindow() {
        // ISC-245, the window half. A pass that took this minute's whole limit leaves the
        // button none: it answers at once with the reason, requests nothing and writes nothing,
        // because a spent minute is a fact about the minute and not about the page.
        String path = "/projekt/busy-minute";
        long id = unfetchedOffer(path);
        stubFor(get(urlEqualTo(path)).willReturn(aResponse().withBody(AD_HTML)));
        int perMinute = config.snapshot().application().enrichment().fetch().rateLimitPerMinute();
        while (window.tryTake(perMinute)) {
            // the parallel run, spending the shared window
        }

        assertThat(mvc.post().uri("/api/v1/offers/{id}/fetch", id))
                .hasStatus(429)
                .bodyText()
                .contains("rate limit");

        // Not the ad and not robots.txt either: the permit is taken before anything is asked.
        verify(0, anyRequestedFor(anyUrl()));
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM fetched_page WHERE url = ?", Integer.class, PORTAL.baseUrl() + path))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT enrichment_note FROM offer WHERE id = ?", String.class, id))
                .isEqualTo("status 403");
    }

    @Test
    void storesTheAdAndDerivesEverythingAgainForThatOffer() {
        // ISC-246. The night derived this offer from the newsletter summary alone — blocks,
        // fields and a score, all stamped under this model — and could not fetch the ad. The
        // button fetches it, and everything derived from the old text has to be derived again
        // from the new one, which is only observable if the stamps move past the press.
        String path = "/projekt/now-answers";
        long id = unfetchedOffer(path);
        jdbc.update("""
            UPDATE offer
            SET content_at = now() - interval '1 day', content_model = 'test-model',
                fields_at = now() - interval '1 day', fields_model = 'test-model',
                scored_at = now() - interval '1 day', score_model = 'test-model'
            WHERE id = ?
            """, id);
        stubFor(get(urlEqualTo(path)).willReturn(aResponse().withBody(AD_HTML)));
        // The database's clock, not the JVM's: every stamp compared below is written by
        // Postgres' now(), and the container's clock need not agree with this process.
        Timestamp start = jdbc.queryForObject("SELECT now()", Timestamp.class);

        MvcTestResult result = mvc.post().uri("/api/v1/offers/{id}/fetch", id).exchange();

        assertThat(result).hasStatusOk();
        Map<String, Object> row = jdbc.queryForMap("""
            SELECT full_text, enrichment_note, rate_eur, remote_percent,
                   content_at, fields_at, scored_at, score_model
            FROM offer WHERE id = ?
            """, id);
        assertThat((String) row.get("full_text")).contains("Logistikunternehmen");
        assertThat(row.get("enrichment_note")).isNull();
        // The patterns' half of enrichment, read from the same page.
        assertThat(((Number) row.get("rate_eur")).intValue()).isEqualTo(95);
        assertThat(((Number) row.get("remote_percent")).intValue()).isEqualTo(80);
        assertThat((Timestamp) row.get("content_at")).isAfter(start);
        assertThat((Timestamp) row.get("fields_at")).isAfter(start);
        assertThat((Timestamp) row.get("scored_at")).isAfter(start);
        assertThat(row.get("score_model")).isEqualTo("test-model");
        // Each of the three model stages was asked, once, by its own question.
        MODEL.verify(1, postRequestedFor(urlPathEqualTo("/chat/completions")).withRequestBody(containing(CLASSIFIER)));
        MODEL.verify(1, postRequestedFor(urlPathEqualTo("/chat/completions")).withRequestBody(containing(EXTRACTOR)));
        MODEL.verify(1, postRequestedFor(urlPathEqualTo("/chat/completions")).withRequestBody(containing(JUDGE)));

        // The answer is the whole entry as the database now has it, so the page replaces its
        // copy rather than patching it.
        assertThat(result)
                .bodyJson()
                .extractingPath("$.offer.id")
                .asNumber()
                .satisfies(n -> assertThat(n.longValue()).isEqualTo(id));
        assertThat(result)
                .bodyJson()
                .extractingPath("$.offer.fullText")
                .asString()
                .contains("Logistikunternehmen");
        assertThat(result).bodyJson().extractingPath("$.score.model").isEqualTo("test-model");
        assertThat(result).bodyJson().extractingPath("$.score.value").isNotNull();
    }

    @Test
    void refusesAnOfferThatAlreadyHasItsAdAndAsksNobody() {
        // ISC-294, the lookup half. A failed fetch records its reason over the enrichment
        // columns, the text among them, so an offer whose ad is already here is refused before
        // a request is made — whatever its page would answer today.
        String path = "/projekt/already-read";
        long id = unfetchedOffer(path);
        jdbc.update(
                "UPDATE offer SET full_text = 'Das Inserat von letzter Woche.', enrichment_note = NULL WHERE id = ?",
                id);
        stubFor(get(urlEqualTo(path)).willReturn(aResponse().withStatus(500)));

        assertThat(mvc.post().uri("/api/v1/offers/{id}/fetch", id))
                .hasStatus(409)
                .bodyText()
                .contains("already here");

        verify(0, getRequestedFor(urlEqualTo(path)));
        assertThat(jdbc.queryForObject("SELECT full_text FROM offer WHERE id = ?", String.class, id))
                .isEqualTo("Das Inserat von letzter Woche.");
    }

    @Test
    void aFailedFetchLeavesTextThatLandedWhileItWasOut() {
        // ISC-294, the race half. Two presses can both pass the lookup; the one whose page
        // fails must not record its reason over the ad the other one stored in the meantime.
        // The other press's text lands while the portal composes its answer: after the lookup,
        // before the refusal is written, by construction rather than by timing.
        String path = "/projekt/raced";
        long id = unfetchedOffer(path);
        stubFor(get(urlEqualTo(path)).willReturn(aResponse().withStatus(404).withTransformers(WhileAnswering.NAME)));
        whileAnswering = () -> jdbc.update(
                "UPDATE offer SET full_text = 'Vom anderen Klick.', enrichment_note = NULL WHERE id = ?", id);

        assertThat(mvc.post().uri("/api/v1/offers/{id}/fetch", id)).hasStatusOk();

        verify(1, getRequestedFor(urlEqualTo(path)));
        assertThat(jdbc.queryForObject("SELECT full_text FROM offer WHERE id = ?", String.class, id))
                .isEqualTo("Vom anderen Klick.");
        assertThat(jdbc.queryForObject("SELECT enrichment_note FROM offer WHERE id = ?", String.class, id))
                .isNull();
    }

    @Test
    void derivesNothingAndAsksNobodyWhenItsStoreWasANoOp() {
        // ISC-323, the anti-claim. The other side of the race: this press's page answers, and
        // the other press's text lands while it does, so the store (ISC-294) writes zero rows.
        // Nothing this press could derive would be derived from anything it stored, and the
        // press that stored the text is the one that segments, extracts and scores it. So the
        // row stays exactly as the other press left it — no stamp reset, no block, no field, no
        // score — and the model is asked nothing. The answer is still 200 with the entry, as the
        // failed half of the race answers: the fetch reached the page, and the ad is here.
        String path = "/projekt/raced-and-read";
        long id = unfetchedOffer(path);
        jdbc.update("""
            UPDATE offer
            SET content_at = now() - interval '1 day', content_model = 'test-model',
                fields_at = now() - interval '1 day', fields_model = 'test-model',
                scored_at = now() - interval '1 day', score_model = 'test-model'
            WHERE id = ?
            """, id);
        stubFor(get(urlEqualTo(path)).willReturn(aResponse().withBody(AD_HTML).withTransformers(WhileAnswering.NAME)));
        AtomicReference<String> asTheOtherPressLeftIt = new AtomicReference<>();
        whileAnswering = () -> {
            jdbc.update("UPDATE offer SET full_text = 'Vom anderen Klick.', enrichment_note = NULL WHERE id = ?", id);
            asTheOtherPressLeftIt.set(rowOf(id));
        };

        MvcTestResult result = mvc.post().uri("/api/v1/offers/{id}/fetch", id).exchange();

        assertThat(result).hasStatusOk();
        verify(1, getRequestedFor(urlEqualTo(path)));
        // The whole row, so a reset or a re-derivation is caught whichever column it touches.
        assertThat(rowOf(id)).isEqualTo(asTheOtherPressLeftIt.get());
        MODEL.verify(0, postRequestedFor(urlPathEqualTo("/chat/completions")));
        assertThat(result).bodyJson().extractingPath("$.offer.fullText").isEqualTo("Vom anderen Klick.");
    }

    @Test
    void keepsTheOfferAndRecordsTheNewReasonWhenThePortalAnswers500() {
        // ISC-247, the server-error half. Retried and still 500: an outcome, not an error. The
        // offer stays, the night's reason is replaced by the new one, and the answer is the
        // entry, so the detail page can show the reason beside the button instead of blanking.
        String path = "/projekt/server-error";
        long id = unfetchedOffer(path);
        stubFor(get(urlEqualTo(path)).willReturn(aResponse().withStatus(500)));

        MvcTestResult result = mvc.post().uri("/api/v1/offers/{id}/fetch", id).exchange();

        assertThat(result).hasStatusOk();
        assertThat(result)
                .bodyJson()
                .extractingPath("$.offer.id")
                .asNumber()
                .satisfies(n -> assertThat(n.longValue()).isEqualTo(id));
        assertThat(jdbc.queryForObject("SELECT status FROM offer WHERE id = ?", String.class, id))
                .isEqualTo("PASSED");
        assertThat(jdbc.queryForObject("SELECT enrichment_note FROM offer WHERE id = ?", String.class, id))
                .isEqualTo("status 500");
    }

    @Test
    void keepsTheValuesTheOfferAlreadyHadWhenTheRefetchFails() {
        // A failed fetch answers null for every field it could not read; those nulls must not
        // wipe what the newsletter or FIELDS stored before, or the card forgets its duration.
        String path = "/projekt/fails-again";
        long id = unfetchedOffer(path);
        jdbc.update("UPDATE offer SET duration = '9 Monate', rate_eur = 95, workload = 'Vollzeit' WHERE id = ?", id);
        stubFor(get(urlEqualTo(path)).willReturn(aResponse().withStatus(500)));

        assertThat(mvc.post().uri("/api/v1/offers/{id}/fetch", id).exchange()).hasStatusOk();

        assertThat(jdbc.queryForObject("SELECT duration FROM offer WHERE id = ?", String.class, id))
                .isEqualTo("9 Monate");
        assertThat(jdbc.queryForObject("SELECT rate_eur FROM offer WHERE id = ?", Integer.class, id))
                .isEqualTo(95);
        assertThat(jdbc.queryForObject("SELECT workload FROM offer WHERE id = ?", String.class, id))
                .isEqualTo("Vollzeit");
        assertThat(jdbc.queryForObject("SELECT enrichment_note FROM offer WHERE id = ?", String.class, id))
                .isEqualTo("status 500");
    }

    @Test
    void keepsTheOfferAndRecordsTheNewReasonWhenThePortalTimesOut() {
        // ISC-247, the timeout half. A page that does not answer within the fetch timeout is
        // unreachable, and that replaces the night's "status 403" the same way a 500 does.
        String path = "/projekt/never-answers";
        long id = unfetchedOffer(path);
        stubFor(get(urlEqualTo(path))
                .willReturn(aResponse().withBody(AD_HTML).withFixedDelay(2 * FETCH_TIMEOUT_MILLIS)));

        MvcTestResult result = mvc.post().uri("/api/v1/offers/{id}/fetch", id).exchange();

        assertThat(result).hasStatusOk();
        assertThat(result)
                .bodyJson()
                .extractingPath("$.offer.id")
                .asNumber()
                .satisfies(n -> assertThat(n.longValue()).isEqualTo(id));
        assertThat(jdbc.queryForObject("SELECT status FROM offer WHERE id = ?", String.class, id))
                .isEqualTo("PASSED");
        assertThat(jdbc.queryForObject("SELECT enrichment_note FROM offer WHERE id = ?", String.class, id))
                .startsWith("unreachable");
    }

    @Test
    void namesAPageThatWasReadButYieldedNoAdText() {
        // ISC-321. The page answers and the patterns find the rate on it, but the full_text
        // selector matches nothing. Recorded without a note, the card has no reason to show
        // beside its button and the toast ends in an empty one; the note has to say that the
        // page was read and still gave no advert.
        String path = "/projekt/no-article";
        long id = unfetchedOffer(path);
        stubFor(get(urlEqualTo(path)).willReturn(aResponse().withBody(PAGE_WITHOUT_AD_TEXT)));

        MvcTestResult result = mvc.post().uri("/api/v1/offers/{id}/fetch", id).exchange();

        assertThat(result).hasStatusOk();
        assertThat(jdbc.queryForObject("SELECT enrichment_note FROM offer WHERE id = ?", String.class, id))
                .isEqualTo(EnrichmentService.NO_AD_TEXT);
        // What the page did yield is kept: the note says what is missing, not that nothing came.
        assertThat(jdbc.queryForObject("SELECT rate_eur FROM offer WHERE id = ?", Integer.class, id))
                .isEqualTo(95);
        // The refetch toast's reason is this field of the answer.
        assertThat(result)
                .bodyJson()
                .extractingPath("$.offer.enrichmentNote")
                .asString()
                .isEqualTo(EnrichmentService.NO_AD_TEXT);
        assertThat(result).bodyJson().extractingPath("$.offer.fullText").isNull();
    }

    @Test
    void writesNothingButItsOwnOfferAndItsOwnCacheEntryAndStartsNoRun() {
        // ISC-248, the anti-claim. Two offers the night gave up on; the button is pressed for
        // one. The other row must come out exactly as it went in — the whole row, so a stage
        // that widened its WHERE to "everything due" is caught whichever column it touches —
        // and nothing may count as a pipeline run.
        String path = "/projekt/pressed";
        long pressed = unfetchedOffer(path);
        long other = unfetchedOffer("/projekt/not-pressed");
        // Derived, so a reset that reached past its own id has something to clear: an offer
        // with nothing stamped would come out identical whatever touched it.
        jdbc.update("""
            UPDATE offer SET content_at = now() - interval '1 day', content_model = 'test-model',
                             fields_at = now() - interval '1 day', fields_model = 'test-model'
            WHERE id = ?
            """, other);
        stubFor(get(urlEqualTo(path)).willReturn(aResponse().withBody(AD_HTML)));
        String before = rowOf(other);
        int runsBefore = jdbc.queryForObject("SELECT count(*) FROM pipeline_run", Integer.class);

        assertThat(mvc.post().uri("/api/v1/offers/{id}/fetch", pressed)).hasStatusOk();

        assertThat(rowOf(other)).isEqualTo(before);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM offer_score_reason WHERE offer_id = ?", Integer.class, other))
                .isZero();
        assertThat(jdbc.queryForList("SELECT url FROM fetched_page", String.class))
                .containsExactly(PORTAL.baseUrl() + path);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pipeline_run", Integer.class))
                .isEqualTo(runsBefore);
        // And the pressed one was written at all, or the rest proves nothing.
        assertThat(jdbc.queryForObject("SELECT full_text FROM offer WHERE id = ?", String.class, pressed))
                .contains("Logistikunternehmen");
    }

    /**
     * An offer the night already tried and gave up on: passed, stamped, no full text, and the
     * reason in its note.
     */
    private long unfetchedOffer(String path) {
        return jdbc.queryForObject("""
            INSERT INTO offer (source_id, external_id, title, url, fingerprint, status,
                               enriched_at, enrichment_note)
            VALUES (?, ?, 'Senior Java Entwickler (m/w/d)', ?, 'senior java entwickler', 'PASSED',
                    now(), 'status 403')
            RETURNING id
            """, Long.class, sourceId, path, PORTAL.baseUrl() + path);
    }

    /** The whole row as Postgres renders it, so a comparison misses no column. */
    private String rowOf(long id) {
        return jdbc.queryForObject("SELECT to_jsonb(o)::text FROM offer o WHERE o.id = ?", String.class, id);
    }

    /**
     * One stub per stage, each routed by a phrase only its own system prompt carries, each
     * answering in the shape its reader expects: nothing to hide for the classifier, the three
     * facts for the extractor, a role fit for the judge.
     */
    private static void modelAnswersEveryStage() {
        answers(CLASSIFIER, """
            {"blocks":[]}
            """);
        answers(EXTRACTOR, """
            {"start":{"text":"ab 01.10.2026","date":"2026-10-01"},
             "duration":{"text":"12 Monate","months":12},
             "deadline":{"text":null,"date":null}}
            """);
        answers(JUDGE, """
            {"reasons":[{"factor":"role_fit","label":"backend engagement, the target role","points":15}]}
            """);
    }

    /**
     * A complete chat-completion envelope, not the one field the reader needs: the provider
     * SDK deserialises the whole object, and a stage that receives less reports "no answer"
     * rather than failing — see {@code ScoringWithAModelTest.answers}.
     */
    private static void answers(String promptMarker, String content) {
        try {
            String body = JSON.writeValueAsString(Map.of(
                    "id",
                    "chatcmpl-1",
                    "object",
                    "chat.completion",
                    "created",
                    1,
                    "model",
                    "test-model",
                    "choices",
                    List.of(Map.of(
                            "index",
                            0,
                            "message",
                            Map.of("role", "assistant", "content", content),
                            "finish_reason",
                            "stop"))));
            MODEL.stubFor(post(urlPathEqualTo("/chat/completions"))
                    .withRequestBody(containing(promptMarker))
                    .willReturn(aResponse()
                            .withHeader("Content-Type", "application/json")
                            .withBody(body)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The shipped defaults with every {@code llm} value named and the fetch timeout shortened.
     * No vendor is named: an OpenAI-compatible endpoint is the stub.
     */
    private static Path configPointingAtTheStub() {
        try {
            Path dir = Files.createTempDirectory("leadgen-refetch");
            dir.toFile().deleteOnExit();
            ConfigFixtures.materialize(dir);

            Path pipeline = dir.resolve("pipeline.yaml");
            String text = Files.readString(pipeline, StandardCharsets.UTF_8);
            text = set(text, "llm", "provider", "openai-compatible");
            text = set(text, "llm", "base_url", MODEL.baseUrl());
            text = set(text, "llm", "api_key", "test-key");
            text = set(text, "llm", "timeout", "PT5S");
            text = set(text, "llm", "batch", "false");
            text = set(text, "models", "extraction", "''");
            text = set(text, "models", "content", "''");
            text = set(text, "models", "fields", "''");
            text = set(text, "models", "scoring", "test-model");
            text = set(text, "models", "scoring_options", "''");
            text = set(text, "models", "writing", "''");
            text = set(text, "models", "embedding", "''");
            text = set(text, "fetch", "timeout", "PT" + FETCH_TIMEOUT_MILLIS / 1000 + "S");
            text = set(text, "llm", "concurrency", "1");
            text = set(text, "fetch", "concurrency", "1");
            // Every one named or closed, or the resolver fills the rest from whoever's `.env`
            // this runs on — including a key added after this fixture was written.
            text = ConfigFixtures.closePlaceholders(text);
            if (text.contains("${")) {
                throw new IllegalStateException("a placeholder is still open in the test pipeline.yaml");
            }
            Files.writeString(pipeline, text, StandardCharsets.UTF_8);
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Rewrites one scalar, addressed by its key and the nearest enclosing section before it.
     *
     * <p>The section is needed because {@code timeout} occurs twice, under {@code llm} and under
     * {@code enrichment.fetch}. The key is matched by a pattern indifferent to alignment, and a
     * key that is not found throws here — the reason is on {@code ScoringWithAModelTest.set}.
     */
    private static String set(String yaml, String section, String key, String value) {
        Matcher start =
                Pattern.compile("(?m)^[ \\t]*" + Pattern.quote(section) + ":").matcher(yaml);
        Matcher line =
                Pattern.compile("(?m)^([ \\t]*)" + Pattern.quote(key) + ":.*$").matcher(yaml);
        if (!start.find() || !line.find(start.end())) {
            throw new IllegalStateException("no `" + section + ": … " + key
                    + ":` in the shipped pipeline.yaml — the fixture and the file have drifted");
        }
        return yaml.substring(0, line.start()) + line.group(1) + key + ": " + value + yaml.substring(line.end());
    }

    /** Runs {@link #whileAnswering} as a stub's answer is built, and changes nothing about it. */
    static final class WhileAnswering implements ResponseDefinitionTransformerV2 {

        static final String NAME = "while-answering";

        @Override
        public ResponseDefinition transform(ServeEvent serveEvent) {
            whileAnswering.run();
            return serveEvent.getResponseDefinition();
        }

        @Override
        public boolean applyGlobally() {
            return false;
        }

        @Override
        public String getName() {
            return NAME;
        }
    }
}
