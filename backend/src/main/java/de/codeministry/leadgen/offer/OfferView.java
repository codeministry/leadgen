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
 * <p>Three of the fields come in pairs — {@code startsOn}/{@code startText},
 * {@code duration}/{@code durationMonths}, {@code applyBy}/{@code applyByText}. The phrase is
 * what a person reads and is often the whole truth ("ab sofort", "zunächst 6 Monate mit
 * Option"); the normalised half is what a sort key and a filter can compare. Either may be
 * null on its own: a phrase with no resolvable date is the ordinary case.
 *
 * @param sourceName    the source this row was read from, as {@code sources.yaml} names it and as
 *                      the sources screen lists it. Provenance rather than content: {@code portal} is
 *                      who advertises the project, this is which configured input delivered it here.
 * @param archivedAt    when this left the working list, or null while it is still on it.
 * @param archiveSource who took it off, or why it is exempt from the age rule. Carried
 *                      beside the timestamp because the two together are four states and not two, and a
 *                      screen showing "restore" on an offer a person deliberately restored is a screen
 *                      offering to undo nothing.
 */
public record OfferView(
        long id,
        String sourceName,
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
        String startText,
        String duration,
        Integer durationMonths,
        LocalDate applyBy,
        String applyByText,
        String workload,
        String language,
        String fullText,
        String packageDir,
        Instant archivedAt,
        String archiveSource) {}
