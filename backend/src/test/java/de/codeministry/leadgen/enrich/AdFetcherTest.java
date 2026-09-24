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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.verify;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import de.codeministry.leadgen.config.model.PipelineConfig.Enrichment.Fetch;
import java.time.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The gates in front of one fetch, in their order, and the per-pass budget.
 *
 * <p>Separate from {@link EnrichmentServiceTest}, which is about what the stage does with a
 * result. This is about the gates in front of it, and it needs no database: the cache is
 * stubbed to miss every time, which is exactly the condition the limiter exists for.
 *
 * <p>The sliding window itself is pinned in {@link FetchWindowTest}; it moved there when it
 * became one shared bean. What is pinned here is what the fetcher does with it: a pass whose
 * budget is spent <b>refuses rather than waits</b>, cache and robots.txt come before a permit,
 * and a fresh fetch passes the cache but neither robots.txt nor the window.
 */
class AdFetcherTest {

    private static final WireMockServer PORTAL =
            new WireMockServer(WireMockConfiguration.options().dynamicPort());

    static {
        PORTAL.start();
    }

    /**
     * A clock the test moves by hand. A sliding window differs from a fixed one only at a
     * boundary, and waiting sixty seconds to prove it is a test nobody runs.
     */
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
        return fetcher(perMinute, perMinute, cache, timeout);
    }

    /**
     * Separate budget and limit, which is the pair every waiting question is about.
     */
    private AdFetcher fetcher(int perMinute, int maxPerRun, PageCache cache, Duration timeout) {
        return fetcher(perMinute, maxPerRun, cache, timeout, window());
    }

    private AdFetcher fetcher(int perMinute, int maxPerRun, PageCache cache, Duration timeout, FetchWindow window) {
        Fetch settings = new Fetch(timeout, perMinute, maxPerRun, "leadgen-test", Duration.ofDays(7), true);
        return new AdFetcher(settings, cache, window);
    }

    /**
     * A window of its own per fetcher unless a test shares one on purpose. The wait is served
     * by moving the same clock the window is read from, so no test sleeps for a minute.
     */
    private FetchWindow window() {
        return new FetchWindow(clock) {
            @Override
            void pause(Duration wait) {
                waited.add(wait);
                clock.advance(wait);
            }
        };
    }

    private final List<Duration> waited = new ArrayList<>();

    private String url(int n) {
        return PORTAL.baseUrl() + "/ad?n=" + n;
    }

    @Test
    void refusesTheRequestOnceTheRunsBudgetIsSpentRatherThanWaitingForever() {
        // `max_per_run` is what says how long a pass is prepared to wait. Spent, it refuses
        // immediately: the alternative is a stage that blocks until the backlog is gone,
        // and a backlog is measured in hundreds. The offer stays in and is due again.
        AdFetcher fetcher = fetcher(3);
        for (int i = 0; i < 3; i++) {
            assertThat(fetcher.fetch(url(i)).succeeded()).isTrue();
        }

        long before = System.nanoTime();
        FetchResult refused = fetcher.fetch(url(99));
        long tookMillis = (System.nanoTime() - before) / 1_000_000;

        assertThat(refused.succeeded()).isFalse();
        assertThat(refused.note()).containsIgnoringCase("budget");
        assertThat(refused.fromCache()).isFalse();
        assertThat(tookMillis).isLessThan(200);
        PORTAL.verify(3, getRequestedFor(urlPathEqualTo("/ad")));
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

    @Test
    void waitsForTheWindowWhileTheRunStillHasBudgetForTheAd() {
        // The fetcher's half of the waiting question: with budget left it waits for the
        // window rather than deferring. How long, and for which permit, is FetchWindowTest's.
        AdFetcher fetcher = fetcher(3, 5, mock(PageCache.class), Duration.ofSeconds(5));
        for (int i = 0; i < 4; i++) {
            assertThat(fetcher.fetch(url(i)).succeeded()).isTrue();
        }

        assertThat(waited).hasSize(1);
        PORTAL.verify(4, getRequestedFor(urlPathEqualTo("/ad")));
    }

    @Test
    void twoFetchersDrawFromOneWindow() {
        // The reason the window is a bean. A window per fetcher let a run and a button
        // together ask a portal twice as often as the limit says; sharing one, the second
        // finds the minute the first one spent, and its budget of its own does not help it.
        FetchWindow shared = window();
        AdFetcher run = fetcher(3, 3, mock(PageCache.class), Duration.ofSeconds(5), shared);
        AdFetcher button = fetcher(3, 3, mock(PageCache.class), Duration.ofSeconds(5), shared);
        for (int i = 0; i < 3; i++) {
            assertThat(run.fetch(url(i)).succeeded()).isTrue();
        }

        FetchResult refused = button.fetchFresh(url(3));

        assertThat(refused.deferred()).isTrue();
        assertThat(refused.note()).contains("3").containsIgnoringCase("minute");
        assertThat(waited).isEmpty();
        PORTAL.verify(3, getRequestedFor(urlPathEqualTo("/ad")));
    }

    @Test
    void aFreshFetchAsksPastACachedFailureAndStoresTheNewAnswer() {
        // The whole point of the button: a 403 remembered for a week is right for the night
        // and wrong once the operator has seen the page open in a browser.
        PageCache cache = mock(PageCache.class);
        when(cache.find(eq(url(0)), any())).thenReturn(Optional.of(new PageCache.Entry(403, null)));

        FetchResult fresh = fetcher(3, cache).fetchFresh(url(0));

        assertThat(fresh.succeeded()).isTrue();
        assertThat(fresh.fromCache()).isFalse();
        verify(cache, never()).find(anyString(), any());
        verify(cache).store(eq(url(0)), eq(200), contains("ad"));
        PORTAL.verify(1, getRequestedFor(urlPathEqualTo("/ad")));
    }

    @Test
    void aFreshFetchStillAsksRobotsTxtFirstAndRemembersItsRefusal() {
        // The cache is the one gate the button passes. robots.txt is why the tool may fetch
        // at all, and its refusal is remembered exactly as the night remembers it.
        PORTAL.stubFor(
                get(urlPathEqualTo("/robots.txt")).willReturn(aResponse().withBody("User-agent: *\nDisallow: /ad\n")));
        PageCache cache = mock(PageCache.class);

        FetchResult refused = fetcher(3, cache).fetchFresh(url(0));

        assertThat(refused.succeeded()).isFalse();
        assertThat(refused.deferred()).isFalse();
        assertThat(refused.note()).contains("robots.txt");
        verify(cache).store(url(0), 0, null);
        PORTAL.verify(0, getRequestedFor(urlPathEqualTo("/ad")));
    }

    @Test
    void namesTheCauseWhenTheRootExceptionCarriesNoMessage() {
        // A refused connection arrives without a message; the note must not read "unreachable: null".
        Exception refused = new RuntimeException("wrapper", new java.net.ConnectException());

        assertThat(AdFetcher.rootMessage(refused)).isEqualTo("ConnectException");
        assertThat(AdFetcher.rootMessage(new RuntimeException("outer", new IllegalStateException("closed"))))
                .isEqualTo("closed");
    }
}
