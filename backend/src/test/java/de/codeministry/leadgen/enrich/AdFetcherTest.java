/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.enrich;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import de.codeministry.leadgen.config.model.PipelineConfig.Enrichment.Fetch;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The rate limiter, on its own and against a clock that can be moved.
 *
 * <p>Separate from {@link EnrichmentServiceTest}, which is about what the stage does with a
 * result. This is about the gate in front of it, and it needs no database: the cache is
 * stubbed to miss every time, which is exactly the condition the limiter exists for.
 *
 * <p>Two properties are pinned here, and both of them are the difference between this
 * implementation and every off-the-shelf one. It <b>rejects rather than waits</b>: a fetch
 * over the limit fails immediately and the offer stays in the pipeline marked incomplete,
 * because a stage that blocks would turn a busy minute into a stalled run. And the window
 * <b>slides</b>: twenty a minute means twenty in any sixty seconds, not twenty at the top of
 * each minute and forty across the boundary.
 */
class AdFetcherTest {

    private static final WireMockServer PORTAL =
            new WireMockServer(WireMockConfiguration.options().dynamicPort());

    static {
        PORTAL.start();
    }

    /** A clock the test moves by hand. A sliding window differs from a fixed one only at a
     * boundary, and waiting sixty seconds to prove it is a test nobody runs. */
    private static final class TestClock extends Clock {
        private Instant now = Instant.parse("2026-09-02T12:00:00Z");

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public Instant instant() {
            return now;
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

    private final TestClock clock = new TestClock();

    @AfterAll
    static void stop() {
        PORTAL.stop();
    }

    @BeforeEach
    void reset() {
        PORTAL.resetAll();
        PORTAL.stubFor(get(urlPathEqualTo("/robots.txt")).willReturn(aResponse().withStatus(404)));
        PORTAL.stubFor(get(urlPathEqualTo("/ad")).willReturn(aResponse().withBody("<html><body>ad</body></html>")));
    }

    private AdFetcher fetcher(int perMinute) {
        return fetcher(perMinute, mock(PageCache.class));
    }

    private AdFetcher fetcher(int perMinute, PageCache cache) {
        return fetcher(perMinute, cache, Duration.ofSeconds(5));
    }

    private AdFetcher fetcher(int perMinute, PageCache cache, Duration timeout) {
        Fetch settings = new Fetch(timeout, perMinute, "leadgen-test", Duration.ofDays(7), true);
        return new AdFetcher(settings, cache, clock);
    }

    private String url(int n) {
        return PORTAL.baseUrl() + "/ad?n=" + n;
    }

    @Test
    void refusesTheRequestOverTheLimitRatherThanWaitingForATokenToFreeUp() {
        // Waiting would be the friendlier-looking choice and the wrong one: the fetch runs
        // inside a run somebody is watching, and a stage that blocks turns a busy minute
        // into a stalled pipeline. The offer stays in, marked incomplete, with a reason.
        AdFetcher fetcher = fetcher(3);
        for (int i = 0; i < 3; i++) {
            assertThat(fetcher.fetch(url(i)).succeeded()).isTrue();
        }

        long before = System.nanoTime();
        FetchResult refused = fetcher.fetch(url(99));
        long tookMillis = (System.nanoTime() - before) / 1_000_000;

        assertThat(refused.succeeded()).isFalse();
        assertThat(refused.note()).containsIgnoringCase("rate");
        assertThat(refused.fromCache()).isFalse();
        assertThat(tookMillis).isLessThan(200);
        PORTAL.verify(3, getRequestedFor(urlPathEqualTo("/ad")));
    }

    @Test
    void freesOneTokenSixtySecondsAfterTheRequestThatTookIt() {
        // The sliding half. A fixed window would free all three at the top of the next
        // minute and allow six inside one real minute; this frees exactly the one that has
        // aged out, and does it at that request's own sixtieth second.
        AdFetcher fetcher = fetcher(3);
        fetcher.fetch(url(0));
        clock.advance(Duration.ofSeconds(20));
        fetcher.fetch(url(1));
        fetcher.fetch(url(2));

        assertThat(fetcher.fetch(url(3)).succeeded()).isFalse();

        // Past the first request's sixtieth second, and only that one token is back.
        clock.advance(Duration.ofSeconds(41));
        assertThat(fetcher.fetch(url(4)).succeeded()).isTrue();
        assertThat(fetcher.fetch(url(5)).succeeded()).isFalse();
    }

    @Test
    void servesACachedPageWithoutSpendingATokenOnIt() {
        // The gate order is the whole economics of this stage: cache first, and a hit costs
        // neither a request nor a token. Measured the other way round, a daily run would
        // spend its budget re-reading pages it already has, and the ads it has not seen
        // would be the ones it never got to.
        PageCache cache = mock(PageCache.class);
        when(cache.find(eq(url(0)), any())).thenReturn(Optional.of(new PageCache.Entry(200, "<html>cached</html>")));
        AdFetcher fetcher = fetcher(1, cache);

        FetchResult cached = fetcher.fetch(url(0));

        assertThat(cached.succeeded()).isTrue();
        assertThat(cached.fromCache()).isTrue();
        PORTAL.verify(0, getRequestedFor(urlPathEqualTo("/ad")));
        // The single token is still there, so the one fresh page of the budget still works.
        assertThat(fetcher.fetch(url(1)).succeeded()).isTrue();
    }

    @Test
    void remembersAForbiddenPageAndForgetsATimeout() {
        // A 403 is a fact about the page and worth a week; a timeout is a fact about the
        // moment, and remembering it for a week turns one bad minute into a lost week.
        PageCache forbidden = mock(PageCache.class);
        PORTAL.stubFor(get(urlPathEqualTo("/gone")).willReturn(aResponse().withStatus(403)));
        fetcher(10, forbidden).fetch(PORTAL.baseUrl() + "/gone");
        verify(forbidden).store(anyString(), eq(403), any());

        PageCache slow = mock(PageCache.class);
        PORTAL.stubFor(get(urlPathEqualTo("/slow"))
                .willReturn(aResponse().withFixedDelay(400).withBody("late")));
        FetchResult timedOut = fetcher(10, slow, Duration.ofMillis(80)).fetch(PORTAL.baseUrl() + "/slow");

        assertThat(timedOut.succeeded()).isFalse();
        verify(slow, never()).store(anyString(), anyInt(), any());
    }

    @Test
    void asksAgainAfterAServerErrorAndOnlyOnceAfterARefusal() {
        // The gap this closes: there was no retry anywhere, and a portal having a bad
        // second cost an offer its enrichment for the whole week the cache remembers.
        //
        // Only a 5xx and a transport failure are worth repeating. A 403 is an answer about
        // the page, and asking again is both pointless and rude.
        PORTAL.stubFor(get(urlPathEqualTo("/flaky"))
                .inScenario("flaky")
                .whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo("recovered"));
        PORTAL.stubFor(get(urlPathEqualTo("/flaky"))
                .inScenario("flaky")
                .whenScenarioStateIs("recovered")
                .willReturn(aResponse().withBody("<html><body>ad</body></html>")));
        PORTAL.stubFor(get(urlPathEqualTo("/refused")).willReturn(aResponse().withStatus(403)));

        assertThat(fetcher(10).fetch(PORTAL.baseUrl() + "/flaky").succeeded()).isTrue();
        PORTAL.verify(2, getRequestedFor(urlPathEqualTo("/flaky")));

        assertThat(fetcher(10).fetch(PORTAL.baseUrl() + "/refused").succeeded()).isFalse();
        PORTAL.verify(1, getRequestedFor(urlPathEqualTo("/refused")));
    }

    @Test
    void countsRobotsTxtOutsideTheLimit() {
        // robots.txt is what makes the rest polite, so it must not be able to exhaust the
        // budget it protects. Three ads against a limit of three still all succeed, even
        // though four requests left the machine.
        AdFetcher fetcher = fetcher(3);
        for (int i = 0; i < 3; i++) {
            assertThat(fetcher.fetch(url(i)).succeeded()).isTrue();
        }
        PORTAL.verify(1, getRequestedFor(urlPathEqualTo("/robots.txt")));
    }
}
