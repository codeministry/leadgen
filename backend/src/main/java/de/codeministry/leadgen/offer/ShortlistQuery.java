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
 * What the shortlist screen is asking for.
 *
 * <p>A record rather than ten parameters on a method, because the same ten travel from
 * the controller to the query and back out as the next cursor.
 *
 * <p><b>The sort and the start window are enums, the band is still a string.</b> That is not
 * an inconsistency left lying around: the type is the allowlist, so an enum means nothing
 * unvetted can reach the SQL, and both of the new ones compose SQL. The band names a range
 * whose boundaries the server reads from the configuration either way, and an unrecognised
 * band quietly meaning "all" is a defensible reading of a range where an unrecognised window
 * quietly breaks a partition.
 *
 * @param q            free text over title, description and tags. Null or blank means no search.
 * @param band         `shortlist`, `review`, or anything else for all of them. The two boundaries
 *                     are the configured thresholds and are not stated here: naming them in a request would
 *                     be the browser deciding what a band is, which is what moved this to the server.
 * @param portal       a portal the offer or one of its duplicates was advertised on.
 * @param archived     which side of the archive to show. False is the working list, which is
 *                     what every other screen means by "the shortlist"; true is what has been taken off it,
 *                     by age or by hand. Not a band: a band is a range of scores, and this decides which set
 *                     the bands are applied to.
 * @param sort         which order, which is also which cursor. See {@link ShortlistSort}.
 * @param startWindow  when the engagement starts, as one of four values that partition the set.
 * @param minMonths    the committed minimum length, in months. <b>Excludes offers that stated
 *                     nothing</b>, deliberately and unlike {@code deadlineOpen}: "at least six months" is a
 *                     claim about the offer, and an offer that says nothing does not make it. Zero and
 *                     negative normalise to no filter, the way an absent limit does.
 * @param deadlineOpen only offers whose application deadline has not passed. <b>Includes
 *                     offers that stated nothing</b>, deliberately: "still open" is the absence of proof
 *                     that it closed. The opposite null treatment from {@code minMonths}, one clause away
 *                     from it, which is exactly the pair a later tidy-up harmonises into a bug.
 * @param cursor       the last row of the previous page, or null for the first.
 * @param limit        how many rows to return.
 */
public record ShortlistQuery(
    String q,
    String band,
    String portal,
    boolean archived,
    ShortlistSort sort,
    StartWindow startWindow,
    Integer minMonths,
    boolean deadlineOpen,
    String cursor,
    int limit) {

    /**
     * Fifty is a screenful and a bit, which is what the list loads as you scroll.
     */
    public static final int DEFAULT_LIMIT = 50;

    /**
     * A ceiling, so a hand-written request cannot ask for the whole archive again.
     */
    public static final int MAX_LIMIT = 200;

    public ShortlistQuery {
        limit = limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        // Null rather than zero, so every reader asks one question — "is there a minimum" —
        // instead of two. Zero would also be a filter that removes nothing while looking
        // like one that is switched on.
        minMonths = minMonths == null || minMonths <= 0 ? null : minMonths;
        sort = sort == null ? ShortlistSort.SCORE : sort;
        startWindow = startWindow == null ? StartWindow.ANY : startWindow;
    }

    /**
     * The first page of everything, in the default order. The shape most tests want, and the
     * reason they do not each carry ten arguments.
     */
    public static ShortlistQuery first() {
        return new ShortlistQuery(null, null, null, false, null, null, null, false, null, DEFAULT_LIMIT);
    }

    public ShortlistQuery withCursor(String next) {
        return new ShortlistQuery(q, band, portal, archived, sort, startWindow, minMonths, deadlineOpen, next, limit);
    }

    public ShortlistQuery withLimit(int rows) {
        return new ShortlistQuery(q, band, portal, archived, sort, startWindow, minMonths, deadlineOpen, cursor, rows);
    }

    public ShortlistQuery withSort(ShortlistSort order) {
        return new ShortlistQuery(q, band, portal, archived, order, startWindow, minMonths, deadlineOpen, cursor, limit);
    }
}
