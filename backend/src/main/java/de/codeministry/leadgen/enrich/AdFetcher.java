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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
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
    private final Deque<Instant> recentRequests = new ArrayDeque<>();

    /**
     * The window's only source of time.
     *
     * <p>Injectable for one reason: a sliding window differs from a fixed one exactly at a
     * minute boundary, and a test that has to wait sixty seconds to say so is a test nobody
     * runs. It is a field rather than a parameter because the window is stateful and every
     * reading has to come from the same clock as the entries already in it.
     */
    private final Clock clock;

    public AdFetcher(Fetch settings, PageCache cache) {
        this(settings, cache, Clock.systemUTC());
    }

    AdFetcher(Fetch settings, PageCache cache, Clock clock) {
        this.clock = clock;
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
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            return FetchResult.failed(0, "not a URL: " + url);
        }

        Optional<PageCache.Entry> cached = cache.find(url, settings.cacheTtl());
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

        if (settings.respectRobotsTxt() && !robots.allows(uri, settings.userAgent())) {
            // Remembered like any other outcome: without this the next run asks again,
            // and a disallowed path would be requested once per offer per run forever.
            cache.store(url, 0, null);
            return FetchResult.failed(0, "disallowed by robots.txt");
        }

        if (!takeToken()) {
            return FetchResult.failed(
                    0, "rate limit reached, %d requests a minute".formatted(settings.rateLimitPerMinute()));
        }

        try {
            // The retry sits here and nowhere wider. Around `fetch` it would retry past the
            // cache and past the rate limiter, which is to say it would spend tokens the
            // limiter had already refused and ask a portal three times for one offer.
            ResponseEntity<String> response = retry.execute(() -> {
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
            // Every attempt failed. A server error that survived the retries is still an
            // answer about the page and is remembered; anything else is a fact about the
            // moment, and remembering one bad minute for a week is worse than asking again
            // tomorrow.
            if (e.getCause() instanceof TransientAnswer transient5xx) {
                cache.store(url, transient5xx.status, null);
                return FetchResult.failed(transient5xx.status, "status " + transient5xx.status);
            }
            return FetchResult.failed(0, "unreachable: " + rootMessage(e));
        } catch (RuntimeException e) {
            return FetchResult.failed(0, "unreachable: " + rootMessage(e));
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

    /** A 5xx, wrapped so the retry policy can tell it from an answer worth keeping. */
    private static final class TransientAnswer extends RuntimeException {
        private final int status;

        private TransientAnswer(int status) {
            super("status " + status);
            this.status = status;
        }
    }

    private static String rootMessage(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage();
    }

    /**
     * A sliding window rather than a fixed one: twenty a minute has to mean twenty in any
     * sixty seconds, not twenty at the top of each minute and forty across the boundary.
     */
    private synchronized boolean takeToken() {
        Instant now = clock.instant();
        Instant cutoff = now.minus(Duration.ofMinutes(1));
        while (!recentRequests.isEmpty() && recentRequests.peekFirst().isBefore(cutoff)) {
            recentRequests.removeFirst();
        }
        if (recentRequests.size() >= settings.rateLimitPerMinute()) {
            return false;
        }
        recentRequests.addLast(now);
        return true;
    }

    /**
     * robots.txt is fetched outside the rate limit: it is what makes the rest polite.
     *
     * <p>Not retried either. An unreachable robots.txt means allowed, so a retry would only
     * delay the same conclusion — and it is fetched once per host, not once per offer.
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
