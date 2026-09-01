package de.codeministry.leadgen.offer;

import java.util.List;

/**
 * One *project* on the shortlist, which is not the same thing as one listing.
 *
 * <p>{@code sources} holds every portal advertising it, collapsed by deduplication — 12.3
 * % of the measured corpus reaches the pipeline more than once, and a shortlist that shows
 * the same project three times is a shortlist nobody finishes reading.
 */
public record ShortlistEntry(
        OfferView offer, OfferScoreView score, OfferFlags flags, List<OfferSourceRef> sources) {}
