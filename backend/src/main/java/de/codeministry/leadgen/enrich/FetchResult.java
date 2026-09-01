package de.codeministry.leadgen.enrich;

/**
 * One attempt at the original ad.
 *
 * @param status the HTTP status, or 0 when no request was made at all — blocked by
 *     robots.txt, or the host was unreachable. A `fromCache` result carries the status
 *     the cached response had.
 * @param body null unless the fetch succeeded.
 * @param note why there is no body, in words. Null on success.
 */
public record FetchResult(int status, String body, boolean fromCache, String note) {

    public static FetchResult ok(int status, String body, boolean fromCache) {
        return new FetchResult(status, body, fromCache, null);
    }

    public static FetchResult failed(int status, String note) {
        return new FetchResult(status, null, false, note);
    }

    /**
     * A failure the cache already knew about. Separate from {@link #failed} because the
     * interesting property of a cached result is not that it failed but that <em>no
     * request was made</em> — and a cached 403 that reports itself as a fresh one makes
     * the request count in the report a lie.
     */
    public static FetchResult cachedFailure(int status, String note) {
        return new FetchResult(status, null, true, note);
    }

    public boolean succeeded() {
        return body != null;
    }
}
