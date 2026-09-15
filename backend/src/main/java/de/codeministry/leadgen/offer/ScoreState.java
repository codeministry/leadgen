/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.offer;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Whether an offer carries a score at all, as a filter.
 *
 * <p>The number was already on the screen — {@code unscored} rides along with every match
 * count, in the same sentence as the total — and it was the one figure beside the list that
 * could not be clicked. Unscored is not a low score and never was: it is a judge that was not
 * configured, did not answer, or answered with something that was not JSON, and the offers it
 * names are the ones somebody has to look at by hand.
 *
 * <p>An enum and not a string like {@code band}, for {@link StartWindow}'s reason: an
 * unrecognised band quietly meaning "all" is a defensible reading of a range, while an
 * unrecognised state here would quietly hand back the list this filter exists to narrow.
 */
public enum ScoreState {

    /**
     * Scored or not, no clause at all.
     */
    ANY("any", ""),

    /**
     * Only offers a judge has answered for. Redundant beside a band or a range, which is why
     * {@link ScoreFilter} refuses that combination rather than intersecting it.
     */
    SCORED("scored", " AND o.score_value IS NOT NULL\n"),

    /**
     * Only offers with no score. What the count beside the list has always been pointing at.
     */
    UNSCORED("unscored", " AND o.score_value IS NULL\n");

    private final String key;
    private final String clause;

    ScoreState(String key, String clause) {
        this.key = key;
        this.clause = clause;
    }

    public String key() {
        return key;
    }

    String clause() {
        return clause;
    }

    /**
     * The state named, or {@link #ANY} when nothing was named. Anything else is refused, for
     * the same reason an unknown sort is.
     */
    public static ScoreState of(String name) {
        if (name == null || name.isBlank() || ANY.key.equalsIgnoreCase(name.trim())) {
            return ANY;
        }
        return Arrays.stream(values())
            .filter(state -> state.key.equalsIgnoreCase(name.trim()))
            .findFirst()
            .orElseThrow(() -> new BadShortlistRequest("'%s' is not a score state; it has %s".formatted(
                name, Arrays.stream(values()).map(ScoreState::key).collect(Collectors.joining(", ")))));
    }
}
