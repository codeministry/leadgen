/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.enrich;

import de.codeministry.leadgen.config.model.PipelineConfig.Enrichment.Fetch;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.retry.RetryException;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Fetches one original ad, and refuses to more often than it should.
 *
 * <p>Four gates, in this order, because each one is cheaper than the next: the cache, then
 * robots.txt, then the rate limit, then the network. A page served from the cache costs
 * nothing and consumes no rate-limit token — which is the whole point of ISC-47, where a
 * second run inside the TTL must issue no request at all.
 *
 * <p>A {@linkplain #fetchFresh fresh fetch} passes the first gate and none of the others.
 * The cache is what keeps a refusal for a week, and a person who has just seen the page open
 * in a browser knows something the cache does not; robots.txt and the rate limit are why this
 * tool may fetch at all, and nobody's knowledge overrides them.
 *
 * <p>Everything here is failure-tolerant by design. An offer whose ad cannot be read stays
 * in the pipeline marked incomplete; the alternative is discarding a good project because
 * a portal had a bad afternoon.
 */
@Slf4j
public class AdFetcher {

    private final RestClient http;
    private final RetryTemplate retry;
    private final PageCache cache;
    private final Fetch settings;
    private final RobotsPolicy robots;

    /**
     * The rate limit, shared with every other fetcher in the process. See {@link FetchWindow}
     * for why it is not this fetcher's own.
     */
    private final FetchWindow window;

    /**
     * What this fetcher has taken since it was built. One fetcher is one pass, and the budget
     * is the pass's: the window is shared, but how long a pass is prepared to wait is not.
     *
     * <p>Shared by the workers of a pass: {@code EnrichmentService.run} hands this one fetcher to
     * every worker when {@code enrichment.fetch.concurrency} is above one. So it is only read and
     * written under this fetcher's lock, in {@link #reserve}, {@link #release} and
     * {@link #counted}, and a budget unit is reserved before a permit is asked for rather than
     * counted after one was granted: checked afterwards, four workers could each see one unit left
     * and all four take a permit for it.
     *
     * <p>Everything else a pass touches from several workers is immutable (the settings, the
     * clients, the retry template), a bean that is thread-safe on its own ({@link PageCache},
     * {@link FetchWindow}), or {@link RobotsPolicy}'s per-host map, a {@code ConcurrentHashMap}
     * filled by {@code computeIfAbsent}: robots.txt is still read once per host per pass, and a
     * second worker for the same host waits for that one read.
     */
    private int taken;

    public AdFetcher(Fetch settings, PageCache cache, FetchWindow window) {
        this.window = window;
        this.settings = settings;
        this.cache = cache;
        // The JDK client stays underneath, because the two things configured on it are the
        // two that matter here: the connect timeout, and following redirects normally
        // rather than always — `ALWAYS` would allow an HTTPS-to-HTTP downgrade.
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(settings.timeout())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
        factory.setReadTimeout(settings.timeout());
        this.http = RestClient.builder().requestFactory(factory).build();
        this.retry = new RetryTemplate(RetryPolicy.builder()
                // Two extra attempts, not ten. A portal that answers 503 twice in half a
                // second is having a bad minute; one that keeps doing it is having a bad
                // day, and the offer staying incomplete is the correct outcome either way.
                .maxRetries(2)
                .delay(Duration.ofMillis(200))
                .multiplier(2)
                // A 4xx is an answer about the request and repeating it changes nothing.
                // Only a transport failure and a server error are worth asking twice.
                .includes(ResourceAccessException.class, TransientAnswer.class)
                .build());
        this.robots = new RobotsPolicy(this::readRobots);
    }

    public FetchResult fetch(String url) {
        return fetch(url, false);
    }

    /**
     * The same fetch without the cache in front of it, and without waiting for a permit.
     *
     * <p>The cache is skipped on the way in only. Whatever the page answers is stored exactly
     * as {@link #fetch} stores it, so the night after reads the new answer rather than the old
     * refusal. robots.txt still comes first, and its refusal is still remembered.
     *
     * <p>The window is asked rather than waited for: a person is watching this one, and a full
     * window is an answer about the minute they can act on, not a reason to hold a request open
     * for sixty seconds. The refusal is {@linkplain FetchResult#deferred deferred}, because it
     * says nothing about the page. A retry after a 5xx asks again, and a refusal there ends the
     * retries with the 5xx the page has already given.
     */
    public FetchResult fetchFresh(String url) {
        return fetch(url, true);
    }

    private FetchResult fetch(String url, boolean fresh) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            return FetchResult.failed(0, "not a URL: " + url);
        }

        Optional<PageCache.Entry> cached = fresh ? Optional.empty() : cache.find(url, settings.cacheTtl());
        if (cached.isPresent()) {
            PageCache.Entry entry = cached.get();
            if (entry.body() != null) {
                return FetchResult.ok(entry.status(), entry.body(), true);
            }
            return FetchResult.cachedFailure(
                    entry.status(),
                    entry.status() == 0
                            ? "disallowed by robots.txt, remembered from an earlier run"
                            : "status " + entry.status() + ", remembered from an earlier run");
        }

        // The fresh fetch takes its permit before robots.txt, the run after it. A run reads
        // robots.txt once per host per pass, so the order costs it nothing; a fresh fetch
        // builds its fetcher per press, and asked robots-first every press would send the
        // portal one unthrottled request even while the window stands spent. Permit-first,
        // a spent minute sends nothing at all, and robots.txt is bounded by the same window.
        if (fresh && !takeNow()) {
            return FetchResult.deferred("the fetch rate limit of %d ads a minute is spent; try again in a minute"
                    .formatted(settings.rateLimitPerMinute()));
        }

        if (settings.respectRobotsTxt() && !robots.allows(uri, settings.userAgent())) {
            // Remembered like any other outcome: without this the next run asks again,
            // and a disallowed path would be requested once per offer per run forever.
            cache.store(url, 0, null);
            return FetchResult.failed(0, "disallowed by robots.txt");
        }
        if (!fresh && !awaitToken()) {
            // Deferred, not failed: the limiter is saying "not in this run", which is a fact
            // about the run and not about the page. Written down as a failure it would stamp
            // `enriched_at` and the offer would never be fetched again.
            return FetchResult.deferred("this run's fetch budget of %d ads is spent".formatted(settings.budget()));
        }

        try {
            // The retry sits here and nowhere wider. Around `fetch` it would retry past the
            // cache and past the rate limiter, which is to say it would spend tokens the
            // limiter had already refused and ask a portal three times for one offer.
            //
            // And each retry is a request, so each one takes a permit of its own. The first
            // attempt's was taken above; one permit per advert would let a permit buy three
            // requests, and a pass of 503s send three times what the window allows.
            int[] attempts = {0};
            ResponseEntity<String> response = retry.execute(() -> {
                if (attempts[0]++ > 0 && !retryPermit(fresh)) {
                    throw new WindowSpent();
                }
                ResponseEntity<String> answer = exchange(uri);
                if (answer.getStatusCode().is5xxServerError()) {
                    throw new TransientAnswer(answer.getStatusCode().value());
                }
                return answer;
            });

            int status = response.getStatusCode().value();
            boolean ok = response.getStatusCode().is2xxSuccessful();
            cache.store(url, status, ok ? response.getBody() : null);
            return ok
                    ? FetchResult.ok(status, response.getBody(), false)
                    : FetchResult.failed(status, "status " + status);
        } catch (RetryException e) {
            // Every attempt failed, or the window refused the next one. A server error that
            // survived the retries is still an answer about the page and is remembered;
            // anything else is a fact about the moment, and remembering one bad minute for a
            // week is worse than asking again tomorrow. A refused retry sent nothing, so what
            // counts is the attempt before it: the failure stays the one it was.
            Throwable last = lastAttempt(e);
            if (last instanceof TransientAnswer transient5xx) {
                cache.store(url, transient5xx.status, null);
                return FetchResult.failed(transient5xx.status, "status " + transient5xx.status);
            }
            return FetchResult.failed(0, "unreachable: " + rootMessage(last));
        } catch (RuntimeException e) {
            return FetchResult.failed(0, "unreachable: " + rootMessage(e));
        }
    }

    /**
     * The failure of the last attempt that sent a request. A {@link WindowSpent} sent nothing and
     * only ever follows an attempt that did, which the template keeps as a suppressed exception.
     */
    private static Throwable lastAttempt(RetryException e) {
        if (!(e.getCause() instanceof WindowSpent)) {
            return e.getCause();
        }
        Throwable[] earlier = e.getSuppressed();
        return earlier[earlier.length - 1];
    }

    /**
     * A permit for a retry. The run waits for it as it waits for its first one; the button asks
     * once, and a refusal ends the retries with the failure the page has already given.
     *
     * <p>Not counted against {@code max_per_run}: the budget is a number of adverts a pass
     * fetches, and this advert's unit was reserved with its first attempt.
     */
    private boolean retryPermit(boolean fresh) {
        return fresh
                ? window.tryTake(settings.rateLimitPerMinute())
                : window.awaitTake(settings.rateLimitPerMinute(), () -> false);
    }

    /**
     * A retry the window refused, or whose wait was interrupted. Not retryable, so the template
     * stops at it; it sent no request and carries no answer of its own.
     */
    private static final class WindowSpent extends RuntimeException {
        private WindowSpent() {
            super("no permit for a retry", null, false, false);
        }
    }

    /**
     * One request, with the default error handling switched off.
     *
     * <p>`RestClient` throws on 4xx and 5xx by default, and here the status <em>is</em> the
     * answer: a 403 is a fact about the page worth remembering for a week, and it has to
     * reach the cache rather than the catch block.
     */
    private ResponseEntity<String> exchange(URI uri) {
        return http.get()
                .uri(uri)
                .header("User-Agent", settings.userAgent())
                .header("Accept", "text/html,application/xhtml+xml")
                .retrieve()
                .onStatus(status -> true, (request, response) -> {})
                .toEntity(String.class);
    }

    /**
     * A 5xx, wrapped so the retry policy can tell it from an answer worth keeping.
     */
    private static final class TransientAnswer extends RuntimeException {
        private final int status;

        private TransientAnswer(int status) {
            super("status " + status);
            this.status = status;
        }
    }

    /**
     * The innermost cause's message, or its class name when it has none: a refused connection
     * arrives without a message, and the note would otherwise read "unreachable: null".
     */
    static String rootMessage(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
    }

    /**
     * A permit, waiting for the window to free one if the run still has budget for it.
     *
     * <p>The limiter refuses rather than waits, which is right for the limiter and wrong for
     * the pass on top of it: a run stopped at one minute's worth and deferred everything
     * else, so a backlog needed one run per {@code rate_limit_per_minute} offers to clear.
     * Measured against the live database: 97 of 101 scored offers had never had their
     * original ad fetched, and the judge was deciding on a three-line newsletter summary.
     *
     * <p>What is <em>not</em> relaxed is the window. This waits for a permit the limiter
     * would have granted anyway; it never takes one it would have refused. Beyond
     * {@code enrichment.fetch.max_per_run} the run gives up and the offer stays due, which
     * is the same outcome as before, one budget later.
     *
     * <p>The budget unit is reserved first and the wait comes after, which makes
     * {@code max_per_run} a hard cap on what one pass sends. It used to be asked only when the
     * window was full, so a pass ran past it whenever the window happened to have room:
     * measured, a budget of 25 over 30 adverts at 20 a minute sent 30. The wait therefore never
     * gives up on its own; it ends with a permit or with an interrupt, and only the interrupt
     * gives the unit back.
     */
    private boolean awaitToken() {
        if (!reserve()) {
            return false;
        }
        if (window.awaitTake(settings.rateLimitPerMinute(), () -> false)) {
            return true;
        }
        release();
        return false;
    }

    /**
     * A permit if the window has one now. The fresh fetch's gate, which never waits.
     */
    private boolean takeNow() {
        return counted(window.tryTake(settings.rateLimitPerMinute()));
    }

    private synchronized boolean counted(boolean took) {
        if (took) {
            taken++;
        }
        return took;
    }

    /**
     * One unit of this pass's budget, or false once the pass has fetched everything it was
     * allowed to. Checked and taken under one lock, because the workers of a pass share it.
     */
    private synchronized boolean reserve() {
        if (taken >= settings.budget()) {
            return false;
        }
        taken++;
        return true;
    }

    /** A reserved unit whose wait was interrupted, and which therefore sent nothing. */
    private synchronized void release() {
        taken--;
    }

    /**
     * robots.txt is fetched outside the rate limit: it is what makes the rest polite.
     *
     * <p>Not retried either. An unreachable robots.txt means allowed, so a retry would only
     * delay the same conclusion — and it is fetched once per host per fetcher, not once per
     * offer. A fresh fetch builds a fetcher per press, which is why it takes its permit first.
     */
    private String readRobots(URI robotsUri) {
        try {
            ResponseEntity<String> response = http.get()
                    .uri(robotsUri)
                    .header("User-Agent", settings.userAgent())
                    .retrieve()
                    .onStatus(status -> true, (request, response2) -> {})
                    .toEntity(String.class);
            return response.getStatusCode().value() == 200 ? response.getBody() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
