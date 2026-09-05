/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.enrich;

/**
 * One attempt at the original ad.
 *
 * @param status the HTTP status, or 0 when no request was made at all — blocked by
 *     robots.txt, or the host was unreachable. A `fromCache` result carries the status
 *     the cached response had.
 * @param body null unless the fetch succeeded.
 * @param note why there is no body, in words. Null on success.
 * @param deferred no attempt was made and none should be remembered. See {@link #deferred}.
 */
public record FetchResult(int status, String body, boolean fromCache, String note, boolean deferred) {

    public static FetchResult ok(int status, String body, boolean fromCache) {
        return new FetchResult(status, body, fromCache, null, false);
    }

    public static FetchResult failed(int status, String note) {
        return new FetchResult(status, null, false, note, false);
    }

    /**
     * Nothing was attempted, and the offer must stay due.
     *
     * <p>The same distinction the cache already makes: a 403 or a disallowed path is a fact
     * about the page and is worth remembering, a timeout is a fact about the moment and is
     * not. A rate-limit refusal is the second kind — it says the run had already asked this
     * portal often enough, which is true of the minute and of nothing else.
     *
     * <p>Recorded like a failure it stamps {@code enriched_at}, and the due query is
     * {@code enriched_at IS NULL}: the offer is then never fetched again and goes on to be
     * scored on the newsletter summary alone. Measured on the first full pass: 480 due, 20
     * fetched, 460 permanently written off with "rate limit reached" and 0 left due.
     */
    public static FetchResult deferred(String note) {
        return new FetchResult(0, null, false, note, true);
    }

    /**
     * A failure the cache already knew about. Separate from {@link #failed} because the
     * interesting property of a cached result is not that it failed but that <em>no
     * request was made</em> — and a cached 403 that reports itself as a fresh one makes
     * the request count in the report a lie.
     */
    public static FetchResult cachedFailure(int status, String note) {
        return new FetchResult(status, null, true, note, false);
    }

    public boolean succeeded() {
        return body != null;
    }
}
