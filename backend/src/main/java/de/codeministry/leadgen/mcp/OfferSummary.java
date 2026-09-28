/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.mcp;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.codeministry.leadgen.offer.OfferView;
import de.codeministry.leadgen.offer.ShortlistEntry;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * One offer as {@code leadgen_search_offers} lists it: the fields a client needs to pick one, never
 * the advert text. Empty values are left out, and the two flags appear only when they are set, as
 * codeministry-mcp's projection did.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
record OfferSummary(
        Long id,
        String title,
        String agency,
        String portal,
        String location,
        Integer remotePercent,
        Integer durationMonths,
        BigDecimal rateEur,
        String startText,
        LocalDate applyBy,
        LocalDate publishedOn,
        Integer score,
        List<String> tags,
        String url,
        Boolean possibleDuplicate,
        Boolean incomplete) {

    static OfferSummary of(ShortlistEntry entry) {
        OfferView offer = entry.offer();
        return new OfferSummary(
                offer.id() == 0 ? null : offer.id(),
                offer.title(),
                offer.agency(),
                offer.portal(),
                offer.location(),
                offer.remotePercent(),
                offer.durationMonths(),
                offer.rateEur(),
                offer.startText(),
                offer.applyBy(),
                offer.publishedOn(),
                entry.score() == null ? null : entry.score().value(),
                offer.tags(),
                offer.url(),
                entry.flags() != null && entry.flags().possibleDuplicate() ? Boolean.TRUE : null,
                entry.flags() != null && entry.flags().incomplete() ? Boolean.TRUE : null);
    }
}
