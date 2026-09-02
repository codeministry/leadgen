/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.offer;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * One listing, as extracted and then enriched.
 *
 * <p>Every enriched field is nullable and null means "not stated", never zero: the
 * newsletter states a rate in 0.0 % of offers, so an unfetched ad is the normal case.
 *
 * @param archivedAt when this left the working list, or null while it is still on it.
 * @param archiveSource who took it off, or why it is exempt from the age rule. Carried
 *     beside the timestamp because the two together are four states and not two, and a
 *     screen showing "restore" on an offer a person deliberately restored is a screen
 *     offering to undo nothing.
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
        String packageDir,
        Instant archivedAt,
        String archiveSource) {}
