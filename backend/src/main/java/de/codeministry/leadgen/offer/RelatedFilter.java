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
 * The relatedness axis: offers near some words, or offers near one offer.
 *
 * <p>A record and not two components on {@link ShortlistQuery}, for the reason
 * {@link ScoreFilter} states: the invariant needs one home, and spread across the query's own
 * compact constructor it would sit beside eight unrelated normalisations and read as one more
 * of them.
 *
 * <p><b>This narrows the set; it never reorders it.</b> A relevance ranking would make the sort
 * key a function of the request and the cursor a function of the query text, and
 * {@link ShortlistSort} and {@link Cursor} exist to prevent exactly that. So the list stays in
 * whichever of the six orders was asked for, and this decides only which offers are in it. The
 * consequence worth stating, because it is invisible from the outside: <b>the first row is not
 * the best match, because there is no such thing here.</b>
 * {@code docs/decisions/retrieval.md} has the argument.
 *
 * @param semantic  free text to find offers near. Null or blank means no relatedness filter.
 * @param similarTo an offer to find offers near. <b>Costs no model call at all</b>, because
 *                  both vectors are already in the database — unlike {@code semantic}, which
 *                  has to be embedded before it can be compared to anything.
 */
public record RelatedFilter(String semantic, Long similarTo) {

    /** No relatedness filter, which is what every request that does not ask for one carries. */
    public static final RelatedFilter ANY = new RelatedFilter(null, null);

    public RelatedFilter {
        semantic = semantic == null || semantic.isBlank() ? null : semantic.trim();

        if (semantic != null && similarTo != null) {
            // Two spellings of one narrowing, and asking for both is not "both at once": the
            // server would have to choose a neighbourhood around two different points and the
            // caller could not tell which one it got.
            throw new BadShortlistRequest(
                    "semantic=%s and similar=%d are two spellings of one filter; ask for one of them, not both"
                            .formatted(semantic, similarTo));
        }
    }

    /** Whether anything is being asked for at all. */
    public boolean asked() {
        return semantic != null || similarTo != null;
    }
}
