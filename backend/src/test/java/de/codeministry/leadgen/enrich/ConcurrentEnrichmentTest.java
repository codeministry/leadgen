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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.extension.Parameters;
import com.github.tomakehurst.wiremock.extension.ServeEventListener;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import de.codeministry.leadgen.Databases;
import de.codeministry.leadgen.config.ConfigFixtures;
import de.codeministry.leadgen.config.ConfigRegistry;
import de.codeministry.leadgen.config.ConfigSnapshot;
import de.codeministry.leadgen.config.model.PipelineConfig;
import de.codeministry.leadgen.config.model.PipelineConfig.Enrichment.Fetch;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * ENRICH with several fetches in flight, and the promises to the portal unchanged by it.
 *
 * <p>Width is the only thing {@code enrichment.fetch.concurrency} is allowed to change. The rate
 * window is a promise to the portal and {@code max_per_run} a promise about the pass; both have
 * to hold under four workers exactly as under one, and a cached refusal or a robots.txt refusal
 * must stay what it was — no request, the row written as it always was.
 *
 * <p>Time is simulated. The window reads a clock that only moves when a worker would have slept,
 * and then only to the instant that worker would have woken at, so "the first minute" is the
 * instants before the first wait, and twenty permits a minute over thirty adverts costs no real
 * minute. The per-minute bound is measured on the simulated instants of the permits the window
 * granted; how many requests reached the portal is counted by WireMock, and the two have to agree.
 */
@SpringBootTest
@Testcontainers
class ConcurrentEnrichmentTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = Databases.postgres();

    private static final int ADVERTS = 30;
    private static final int PER_MINUTE = 20;
    private static final int MAX_PER_RUN = 25;
    private static final int WIDTH = 4;

    private static final Path CONFIG_DIR = shippedDefaults();

    /** When the fixture's 403 was remembered: inside the TTL, and exact to the second. */
    private static final Instant CACHED_AT =
            Instant.now().minus(Duration.ofDays(1)).truncatedTo(ChronoUnit.SECONDS);

    private static final String AD_HTML = """
        <html><body>
          <h1>Senior Java Entwickler (m/w/d)</h1>
          <article>
            Für ein Logistikunternehmen suchen wir Verstärkung.
            Stundensatz 95 EUR/h, Laufzeit 12 Monate, 80 % remote.
          </article>
        </body></html>
        """;

    /** How many advert requests the portal is serving at this moment, and the most it ever did. */
    private static final AtomicInteger IN_FLIGHT = new AtomicInteger();

    private static final AtomicInteger PEAK_IN_FLIGHT = new AtomicInteger();

    private static final WireMockServer PORTAL =
            new WireMockServer(WireMockConfiguration.options().dynamicPort().extensions(new InFlight()));

    static {
        PORTAL.start();
    }

    @Autowired
    private ConfigRegistry registry;

    @Autowired
    private PageCache cache;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private JdbcTemplate jdbc;

    private long sourceId;

    @AfterAll
    static void stopPortal() {
        PORTAL.stop();
    }

    @DynamicPropertySource
    static void configuration(DynamicPropertyRegistry registry) {
        registry.add("leadgen.config-dir", CONFIG_DIR::toString);
    }

    @BeforeEach
    void reset() {
        fixture();
    }

    @Test
    void fourWorkersStayInsideTheWindowAndTheBudgetOfThePass() {
        SimulatedWindow window = new SimulatedWindow();

        EnrichmentReport report = service(WIDTH, window).run();

        // The window: never more than twenty permits inside any sixty simulated seconds, and in
        // particular at most twenty in the first minute, which is where four workers would
        // overshoot if the window let a check and a take drift apart.
        Instant firstMinuteEnds = SimulatedWindow.START.plus(FetchWindow.WINDOW);
        assertThat(window.granted().stream().filter(t -> t.isBefore(firstMinuteEnds)))
                .hasSizeLessThanOrEqualTo(PER_MINUTE);
        assertThat(maxInAnyMinute(window.granted())).isLessThanOrEqualTo(PER_MINUTE);

        // The pass: `max_per_run` requests and not one more, whatever the width.
        assertThat(advertRequests()).isEqualTo(MAX_PER_RUN);
        assertThat(window.granted()).hasSize(MAX_PER_RUN);
        assertThat(report.requests()).isEqualTo(MAX_PER_RUN);
        assertThat(report.deferred()).isEqualTo(ADVERTS - MAX_PER_RUN);
        // A deferral writes nothing, so exactly those are due again.
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM offer WHERE enriched_at IS NULL AND status = 'PASSED'", Integer.class))
                .isEqualTo(ADVERTS - MAX_PER_RUN);

        // And the width is real: several adverts were waiting on the portal at the same time.
        assertThat(PEAK_IN_FLIGHT.get()).isGreaterThan(1).isLessThanOrEqualTo(WIDTH);

        specialAdvertsAreUntouched();
    }

    @Test
    void reportsTheSameNumbersAtWidthOneAndWidthFour() {
        EnrichmentReport sequential = service(1, new SimulatedWindow()).run();
        assertThat(PEAK_IN_FLIGHT.get()).isEqualTo(1);
        specialAdvertsAreUntouched();

        fixture();
        EnrichmentReport concurrent = service(WIDTH, new SimulatedWindow()).run();
        specialAdvertsAreUntouched();

        // The same counts; only the width each ran at differs, and each names its own.
        assertThat(concurrent)
                .usingRecursiveComparison()
                .ignoringFields("width")
                .isEqualTo(sequential);
        assertThat(sequential.width()).isEqualTo(1);
        assertThat(concurrent.width()).isEqualTo(WIDTH);
        assertThat(sequential)
                .isEqualTo(new EnrichmentReport(ADVERTS + 2, MAX_PER_RUN, 2, 1, MAX_PER_RUN, ADVERTS - MAX_PER_RUN));
    }

    /**
     * A retry is a request, so it takes a permit: some adverts answer 503 twice before they answer,
     * and still every request the portal saw was one the window granted, and no minute held more
     * than {@code rate_limit_per_minute} of them.
     */
    @Test
    void everyRetryTakesAPermitOfItsOwn() {
        for (int i = 0; i < ADVERTS; i += 5) {
            flaky("/projekt/" + i);
        }
        SimulatedWindow window = new SimulatedWindow();

        service(WIDTH, window).run();

        int requests = advertRequests();
        // Retries happened: six flaky adverts, at most five of them deferred, two extra each.
        assertThat(requests).isGreaterThan(MAX_PER_RUN);
        assertThat(window.granted()).hasSize(requests);
        assertThat(maxInAnyMinute(window.granted())).isLessThanOrEqualTo(PER_MINUTE);
        specialAdvertsAreUntouched();
    }

    /** 503, 503, then the advert: the retry template's two extra attempts, both needed. */
    private static void flaky(String path) {
        String scenario = "flaky " + path;
        PORTAL.stubFor(get(urlEqualTo(path))
                .atPriority(1)
                .inScenario(scenario)
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo("once"));
        PORTAL.stubFor(get(urlEqualTo(path))
                .atPriority(1)
                .inScenario(scenario)
                .whenScenarioStateIs("once")
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo("twice"));
        PORTAL.stubFor(get(urlEqualTo(path))
                .atPriority(1)
                .inScenario(scenario)
                .whenScenarioStateIs("twice")
                .willReturn(aResponse().withBody(AD_HTML)));
    }

    /**
     * The robots.txt refusal and the cached 403: no request to either, and each row written the
     * way the sequential pass has always written it.
     */
    private void specialAdvertsAreUntouched() {
        PORTAL.verify(0, getRequestedFor(urlPathMatching("/intern/.*")));
        PORTAL.verify(0, getRequestedFor(urlPathEqualTo("/projekt/forbidden")));

        assertThat(jdbc.queryForObject(
                        "SELECT enrichment_note FROM offer WHERE url = ? AND enriched_at IS NOT NULL",
                        String.class,
                        url("/intern/projekt")))
                .contains("robots.txt");
        assertThat(jdbc.queryForObject(
                        "SELECT enrichment_note FROM offer WHERE url = ? AND enriched_at IS NOT NULL",
                        String.class,
                        url("/projekt/forbidden")))
                .contains("403")
                .contains("remembered");
        // The remembered refusal is read, not renewed: its row is exactly as the fixture left it.
        assertThat(jdbc.queryForObject(
                        "SELECT status FROM fetched_page WHERE url = ? AND fetched_at = ?",
                        Integer.class,
                        url("/projekt/forbidden"),
                        Timestamp.from(CACHED_AT)))
                .isEqualTo(403);
    }

    private void fixture() {
        jdbc.update("DELETE FROM offer");
        jdbc.update("DELETE FROM fetched_page");
        jdbc.update("DELETE FROM source");
        sourceId =
                jdbc.queryForObject("INSERT INTO source (name, kind) VALUES ('test', 'file') RETURNING id", Long.class);
        PORTAL.resetAll();
        IN_FLIGHT.set(0);
        PEAK_IN_FLIGHT.set(0);

        PORTAL.stubFor(
                get(urlEqualTo("/robots.txt")).willReturn(aResponse().withBody("User-agent: *\nDisallow: /intern/\n")));
        // Slow enough that four workers overlap on the portal and one worker never does.
        PORTAL.stubFor(get(urlPathMatching("/projekt/.*"))
                .willReturn(aResponse().withBody(AD_HTML).withFixedDelay(60)));
        PORTAL.stubFor(get(urlPathMatching("/intern/.*")).willReturn(aResponse().withBody(AD_HTML)));

        for (int i = 0; i < ADVERTS; i++) {
            passedOffer("/projekt/" + i);
            if (i == 7) {
                passedOffer("/intern/projekt");
            }
            if (i == 13) {
                passedOffer("/projekt/forbidden");
            }
        }
        jdbc.update(
                "INSERT INTO fetched_page (url, status, body, fetched_at) VALUES (?, 403, NULL, ?)",
                url("/projekt/forbidden"),
                Timestamp.from(CACHED_AT));
    }

    private EnrichmentService service(int width, FetchWindow window) {
        PipelineConfig.Enrichment shipped = registry.snapshot().application().enrichment();
        Fetch fetch = new Fetch(
                Duration.ofSeconds(5),
                PER_MINUTE,
                MAX_PER_RUN,
                shipped.fetch().userAgent(),
                Duration.ofDays(7),
                true,
                width);
        var enrichment = new PipelineConfig.Enrichment(true, shipped.after(), fetch, shipped.extract());
        PipelineConfig application = mock(PipelineConfig.class);
        when(application.enrichment()).thenReturn(enrichment);
        ConfigRegistry config = mock(ConfigRegistry.class);
        when(config.snapshot()).thenReturn(new ConfigSnapshot(application, null, null, null, null, Instant.now()));
        return new EnrichmentService(config, cache, jdbcClient, window);
    }

    private int advertRequests() {
        return PORTAL.findAll(getRequestedFor(urlPathMatching("/projekt/.*"))).size();
    }

    /** The most permits inside any sixty seconds, each permit opening a window of its own. */
    private static int maxInAnyMinute(List<Instant> granted) {
        List<Instant> sorted = granted.stream().sorted().toList();
        int most = 0;
        for (int i = 0; i < sorted.size(); i++) {
            Instant end = sorted.get(i).plus(FetchWindow.WINDOW);
            int inside = 0;
            for (int j = i; j < sorted.size() && sorted.get(j).isBefore(end); j++) {
                inside++;
            }
            most = Math.max(most, inside);
        }
        return most;
    }

    private void passedOffer(String path) {
        jdbc.update("""
            INSERT INTO offer (source_id, external_id, title, url, fingerprint, status)
            VALUES (?, ?, 'Senior Java Entwickler (m/w/d)', ?, 'senior java entwickler', 'PASSED')
            """, sourceId, path, url(path));
    }

    private static String url(String path) {
        return PORTAL.baseUrl() + path;
    }

    /**
     * The shared window on a clock that moves only when a worker would have slept, and a record
     * of the simulated instant of every permit it granted.
     */
    private static final class SimulatedWindow extends FetchWindow {

        static final Instant START = Instant.parse("2026-09-25T08:00:00Z");

        private final SimulatedClock clock;
        private final List<Instant> granted = new CopyOnWriteArrayList<>();

        SimulatedWindow() {
            this(new SimulatedClock(START));
        }

        private SimulatedWindow(SimulatedClock clock) {
            super(clock);
            this.clock = clock;
        }

        @Override
        public synchronized boolean tryTake(int perMinute) {
            boolean took = super.tryTake(perMinute);
            if (took) {
                granted.add(clock.instant());
            }
            return took;
        }

        /**
         * To the instant this worker would wake at, not by its wait: four workers waiting on the
         * same aged-out permit wake at the same instant, and then race for it.
         */
        @Override
        void pause(Duration wait) {
            clock.advanceTo(clock.instant().plus(wait));
        }

        List<Instant> granted() {
            return granted;
        }
    }

    /** Read and moved from several workers at once, so the instant is held atomically. */
    private static final class SimulatedClock extends Clock {
        private final AtomicReference<Instant> now;

        SimulatedClock(Instant start) {
            this.now = new AtomicReference<>(start);
        }

        /** Never backwards: a worker whose target has already passed leaves the clock alone. */
        void advanceTo(Instant target) {
            now.updateAndGet(t -> t.isBefore(target) ? target : t);
        }

        @Override
        public Instant instant() {
            return now.get();
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    /** Counts advert requests the portal is still answering, to see whether any overlapped. */
    private static final class InFlight implements ServeEventListener {

        @Override
        public void beforeMatch(ServeEvent serveEvent, Parameters parameters) {
            if (isAdvert(serveEvent)) {
                PEAK_IN_FLIGHT.accumulateAndGet(IN_FLIGHT.incrementAndGet(), Math::max);
            }
        }

        @Override
        public void afterComplete(ServeEvent serveEvent, Parameters parameters) {
            if (isAdvert(serveEvent)) {
                IN_FLIGHT.decrementAndGet();
            }
        }

        @Override
        public String getName() {
            return "in-flight";
        }

        private static boolean isAdvert(ServeEvent serveEvent) {
            return serveEvent.getRequest().getUrl().startsWith("/projekt/");
        }
    }

    /** Created once here, because a {@code @DynamicPropertySource} supplier may run more than once. */
    private static Path shippedDefaults() {
        try {
            Path dir = Files.createTempDirectory("leadgen-concurrent-enrich");
            dir.toFile().deleteOnExit();
            ConfigFixtures.materialize(dir);
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
