package de.codeministry.leadgen.offer;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * One listing, as extracted and then enriched.
 *
 * <p>Every enriched field is nullable and null means "not stated", never zero: the
 * newsletter states a rate in 0.0 % of offers, so an unfetched ad is the normal case.
 */
public record OfferView(
        long id,
        String externalId,
        String title,
        String description,
        String url,
        String location,
        String portal,
        String agency,
        LocalDate publishedOn,
        List<String> tags,
        BigDecimal rateEur,
        Integer remotePercent,
        LocalDate startsOn,
        String duration,
        String workload,
        String language,
        String fullText,
        String packageDir) {}
