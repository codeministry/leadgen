/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.offer;

/**
 * The score axis of the shortlist's filters, as one thing.
 *
 * <p><b>Three spellings of one question, and a request may carry exactly one of them.</b> A
 * band is a range whose two boundaries are the configured thresholds; a range is the same
 * shape with the numbers in the request; and a state asks whether there is a score at all.
 * Carried together they do not contradict each other loudly — {@code band=shortlist} with
 * {@code scoreState=unscored} is simply always empty, and a band inside a range is simply the
 * narrower of the two. Both look exactly like a quiet market from the screen, which is the one
 * failure this repository keeps finding: a short list after filtering is indistinguishable
 * from a filter that worked.
 *
 * <p>So the combination is refused here, with a sentence naming both spellings, rather than
 * intersected. The screen never produces it — the three are one control group with three
 * modes — so the only way to arrive at one is a hand-edited query string, which is exactly the
 * caller who should be told.
 *
 * <p>A record and not four components on {@link ShortlistQuery}, because the invariant needs
 * one home. Spread across the query's own compact constructor it would sit beside seven
 * unrelated normalisations and read as one more of them.
 *
 * @param band  {@code shortlist}, {@code review}, {@code discarded}, or anything else for all of
 *              them. The two
 *              boundaries are the configured thresholds and are deliberately not stated in a
 *              request: naming them would be the browser deciding what a band is.
 * @param min   the lowest score to include, inclusive. <b>Excludes offers with no score</b>,
 *              the same null treatment {@code minMonths} has and for the same reason: "at
 *              least sixty" is a claim about the offer, and an offer nobody judged does not
 *              make it. {@link ScoreState#UNSCORED} is how that set is asked for instead.
 * @param max   the highest score to include, inclusive. Same null treatment.
 * @param state whether the offer carries a score at all.
 */
public record ScoreFilter(String band, Integer min, Integer max, ScoreState state) {

    /**
     * No clause on the score at all. What a request that says nothing about it means.
     */
    public static final ScoreFilter ANY = new ScoreFilter(null, null, null, ScoreState.ANY);

    public ScoreFilter {
        band = band == null || band.isBlank() ? null : band.trim();
        state = state == null ? ScoreState.ANY : state;

        boolean ranged = min != null || max != null;
        if (band != null && ranged) {
            throw new BadShortlistRequest(
                    "a band and a score range are two spellings of one filter; ask for band=%s or for a range, not both"
                            .formatted(band));
        }
        if (band != null && state != ScoreState.ANY) {
            throw new BadShortlistRequest(
                    "band=%s and scoreState=%s cannot both hold: a band is a range of scores, so it already says an offer has one"
                            .formatted(band, state.key()));
        }
        if (ranged && state != ScoreState.ANY) {
            throw new BadShortlistRequest(
                    "a score range and scoreState=%s cannot both hold: a range already says an offer has a score"
                            .formatted(state.key()));
        }
        if (ranged && min != null && max != null && min > max) {
            throw new BadShortlistRequest(
                    "minScore=%d is above maxScore=%d, so nothing can match it".formatted(min, max));
        }
    }

    boolean ranged() {
        return min != null || max != null;
    }
}
