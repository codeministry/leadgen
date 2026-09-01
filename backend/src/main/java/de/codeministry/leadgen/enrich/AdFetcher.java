package de.codeministry.leadgen.enrich;

import de.codeministry.leadgen.config.model.PipelineConfig.Enrichment.Fetch;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;

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

    private final HttpClient http;
    private final PageCache cache;
    private final Fetch settings;
    private final RobotsPolicy robots;
    private final Deque<Instant> recentRequests = new ArrayDeque<>();

    public AdFetcher(Fetch settings, PageCache cache) {
        this.settings = settings;
        this.cache = cache;
        this.http = HttpClient.newBuilder()
                .connectTimeout(settings.timeout())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
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
            return FetchResult.failed(0, "rate limit reached, %d requests a minute".formatted(settings.rateLimitPerMinute()));
        }

        try {
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(uri)
                            .timeout(settings.timeout())
                            .header("User-Agent", settings.userAgent())
                            .header("Accept", "text/html,application/xhtml+xml")
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            boolean ok = response.statusCode() >= 200 && response.statusCode() < 300;
            cache.store(url, response.statusCode(), ok ? response.body() : null);
            return ok
                    ? FetchResult.ok(response.statusCode(), response.body(), false)
                    : FetchResult.failed(response.statusCode(), "status " + response.statusCode());
        } catch (IOException e) {
            // Not cached: a timeout is about the moment, not about the page, and
            // remembering it for a week would turn one bad minute into a lost week.
            return FetchResult.failed(0, "unreachable: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return FetchResult.failed(0, "interrupted");
        }
    }

    /**
     * A sliding window rather than a fixed one: twenty a minute has to mean twenty in any
     * sixty seconds, not twenty at the top of each minute and forty across the boundary.
     */
    private synchronized boolean takeToken() {
        Instant now = Instant.now();
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

    /** robots.txt is fetched outside the rate limit: it is what makes the rest polite. */
    private String readRobots(URI robotsUri) {
        try {
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(robotsUri)
                            .timeout(settings.timeout())
                            .header("User-Agent", settings.userAgent())
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200 ? response.body() : null;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
