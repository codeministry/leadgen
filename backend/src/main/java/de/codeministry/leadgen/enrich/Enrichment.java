package de.codeministry.leadgen.enrich;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * What the original ad added to an offer. Every field is nullable, and null means
 * "the ad did not say", never zero — the whole reason this stage exists is that the
 * newsletter states a rate in 0.0 % of offers, and a missing value has to stay
 * distinguishable from a low one.
 *
 * @param note why the offer is incomplete, or null when the fetch and the extraction
 *     both worked. A failed fetch never discards an offer; it marks it.
 */
public record Enrichment(
        BigDecimal rateEur,
        String duration,
        String workload,
        Integer remotePercent,
        LocalDate startsOn,
        String contact,
        String fullText,
        String note) {

    public static Enrichment incomplete(String note) {
        return new Enrichment(null, null, null, null, null, null, null, note);
    }

    public boolean complete() {
        return note == null;
    }

    /** How many of the seven fields the ad actually yielded. */
    public int fieldCount() {
        int found = 0;
        if (rateEur != null) found++;
        if (duration != null) found++;
        if (workload != null) found++;
        if (remotePercent != null) found++;
        if (startsOn != null) found++;
        if (contact != null) found++;
        if (fullText != null && !fullText.isBlank()) found++;
        return found;
    }
}
