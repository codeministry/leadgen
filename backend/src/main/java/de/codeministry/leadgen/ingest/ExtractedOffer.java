package de.codeministry.leadgen.ingest;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * One offer as the source stated it. No judgement yet — filtering, scoring and
 * enrichment all come later and all read this.
 *
 * @param externalId what identifies the offer at the source. The unwrapped target URL:
 *     the same project reaches the aggregator through several portals under different
 *     URLs, so this identifies a listing, not a project. Collapsing the listings into
 *     one project is deduplication's job, not this one's.
 * @param publishedOn the date only. The source states a time as well, without a zone —
 *     a value like that cannot be turned into an instant without guessing, and the
 *     freshness rule counts days.
 */
public record ExtractedOffer(
        String externalId,
        String title,
        String description,
        String url,
        String location,
        String portal,
        String agency,
        LocalDate publishedOn,
        List<String> tags,
        String fingerprint,
        /**
         * When the document carrying this offer arrived — the mail's own date, not the
         * run's. Null when the source is not a mail. Set by the caller rather than read
         * out of the block: it is a property of the document, and every offer in one
         * document shares it.
         */
        Instant receivedAt) {}
