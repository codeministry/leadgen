package de.codeministry.leadgen.enrich;

/**
 * What one enrichment pass did.
 *
 * @param considered offers that passed the hard filter and were due for enrichment.
 * @param enriched offers whose ad was read and yielded at least one field.
 * @param incomplete offers left in the pipeline with a note. Never discarded — a portal
 *     having a bad afternoon must not cost a good project.
 * @param fromCache answered without a request. On a second run inside the TTL this equals
 *     `considered` and `requests` is zero, which is what ISC-47 asserts.
 * @param requests actual HTTP requests for ads, robots.txt excluded.
 */
public record EnrichmentReport(int considered, int enriched, int incomplete, int fromCache, int requests) {

    public static EnrichmentReport skipped() {
        return new EnrichmentReport(0, 0, 0, 0, 0);
    }
}
