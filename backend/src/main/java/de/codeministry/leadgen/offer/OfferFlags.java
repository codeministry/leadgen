package de.codeministry.leadgen.offer;

/**
 * What the pipeline knows about an offer beyond its fields.
 *
 * <p>Both are reasons to look, never reasons to discard. A failed enrichment leaves the
 * offer in as incomplete, and an unstated remote share survives the filter.
 *
 * @param incomplete the ad was not reachable or not readable, so the enriched half is
 *     missing. `enriched_at` set with no note is the only combination that means complete.
 */
public record OfferFlags(boolean incomplete, boolean remoteUnknown) {}
