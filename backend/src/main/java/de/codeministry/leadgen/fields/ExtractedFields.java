/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.fields;

import java.time.LocalDate;
import java.util.Objects;

/**
 * What one advert says about its start, its length and its deadline.
 *
 * <p>Six components, three pairs. Every one is nullable and <b>null means "the advert did not
 * say"</b>, never zero and never today — the same rule the enriched columns already follow,
 * and the reason this stage exists at all is that a missing value has to stay
 * distinguishable from a stated one.
 *
 * <p>A pair can be half-filled in one direction only: a phrase with no resolvable date is the
 * normal case ("ab sofort", "Q4/2026"), while a date with no phrase would mean the model
 * resolved something it could not quote, which {@link FieldExtractor} does not accept.
 *
 * @param startText      what the advert says about the start, verbatim enough to be read
 * @param startsOn       that start as a calendar day, when it resolves to one
 * @param durationText   what the advert says about the length of the engagement
 * @param durationMonths the committed minimum in months, never the optimistic maximum: "6
 *                       Monate mit Option auf Verlängerung" is six, and the phrase carries the rest
 * @param applyByText    what the advert says about a deadline
 * @param applyBy        that deadline as a calendar day, when it resolves to one
 */
public record ExtractedFields(
        String startText,
        LocalDate startsOn,
        String durationText,
        Integer durationMonths,
        String applyByText,
        LocalDate applyBy) {

    public static ExtractedFields none() {
        return new ExtractedFields(null, null, null, null, null, null);
    }

    /**
     * Whether this says anything at all. An advert that states none of the three is a
     * legitimate and common answer, so it is written and stamped like any other — what it
     * must not do is count as a change and buy a re-judge.
     */
    public boolean isEmpty() {
        return startText == null
                && startsOn == null
                && durationText == null
                && durationMonths == null
                && applyByText == null
                && applyBy == null;
    }

    /**
     * Whether writing this would change the row.
     *
     * <p>Only the two columns that were already populated by the enrichment regexes can
     * differ from what is there; the four new ones are null until this stage writes them, so
     * anything it has to say about them is a change. That is what decides whether {@code
     * score_model} is nulled, and a re-judge is a language-model call — so it is asked
     * against the row rather than assumed.
     */
    public boolean changes(LocalDate startsOnNow, String durationNow) {
        return !Objects.equals(startsOn, startsOnNow)
                || !Objects.equals(durationText, durationNow)
                || startText != null
                || durationMonths != null
                || applyByText != null
                || applyBy != null;
    }
}
