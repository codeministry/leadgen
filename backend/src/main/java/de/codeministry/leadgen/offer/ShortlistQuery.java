/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.offer;

import java.util.List;

/**
 * What the shortlist screen is asking for.
 *
 * <p>A record rather than ten parameters on a method, because the same ten travel from
 * the controller to the query and back out as the next cursor.
 *
 * <p><b>The sort, the start window and the score state are enums; the band is still a
 * string.</b> That is not an inconsistency left lying around: the type is the allowlist, so an
 * enum means nothing unvetted can reach the SQL, and each of those composes SQL. The band
 * names a range whose boundaries the server reads from the configuration either way, and an
 * unrecognised band quietly meaning "all" is a defensible reading of a range where an
 * unrecognised window quietly breaks a partition.
 *
 * @param q            free text over the title, the description, the tags and the advert the
 *                     enrichment stage fetched — the content blocks when the advert was segmented,
 *                     {@code full_text} when it was not. Null or blank means no search.
 * @param score        the score axis, as one thing: a band, a range, or a state. See
 *                     {@link ScoreFilter}, which is also where the rule that only one of the three may be
 *                     asked for lives.
 * @param portals      the portals to include. An offer matches when it or any of its duplicates
 *                     was advertised on one of them, because a project reaching the shortlist through
 *                     portal-c is on portal-c even when portal-a holds the primary. Empty means every
 *                     portal; a single-element list is what one name in the query string becomes, so every
 *                     link written before this was a list keeps working.
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
 * @param related      offers near some words, or near one offer. <b>Narrows and never reorders</b>,
 *                     so the sort is whatever was asked for and the first row is not the best match.
 *                     See {@link RelatedFilter}.
 * @param possibleDuplicates only offers the similarity pass marked as possibly the same
 *                     project as an older one. A reason to look at two offers side by side, never a
 *                     reason to hide one: the merging threshold already took everything it was sure
 *                     about, and what is left is the band where a person decides.
 * @param topic        only offers whose stored score reasons name this profile topic, in any band. The
 *                     scorer decided the match and wrote it down; this reads the column and matches
 *                     nothing itself, so the filter and the score cannot disagree about an alias.
 * @param cursor       the last row of the previous page, or null for the first.
 * @param limit        how many rows to return.
 */
public record ShortlistQuery(
        String q,
        ScoreFilter score,
        List<String> portals,
        boolean archived,
        ShortlistSort sort,
        StartWindow startWindow,
        RelatedFilter related,
        Integer minMonths,
        boolean deadlineOpen,
        boolean possibleDuplicates,
        String topic,
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

    /**
     * Everything but the topic, which is what every caller written before topics existed passes.
     */
    public ShortlistQuery(
            String q,
            ScoreFilter score,
            List<String> portals,
            boolean archived,
            ShortlistSort sort,
            StartWindow startWindow,
            RelatedFilter related,
            Integer minMonths,
            boolean deadlineOpen,
            boolean possibleDuplicates,
            String cursor,
            int limit) {
        this(
                q,
                score,
                portals,
                archived,
                sort,
                startWindow,
                related,
                minMonths,
                deadlineOpen,
                possibleDuplicates,
                null,
                cursor,
                limit);
    }

    public ShortlistQuery {
        limit = limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        // Null rather than zero, so every reader asks one question — "is there a minimum" —
        // instead of two. Zero would also be a filter that removes nothing while looking
        // like one that is switched on.
        minMonths = minMonths == null || minMonths <= 0 ? null : minMonths;
        // Blank is no filter, for the same reason a blank portal name is dropped below.
        topic = topic == null || topic.isBlank() ? null : topic.strip();
        sort = sort == null ? ShortlistSort.SCORE : sort;
        startWindow = startWindow == null ? StartWindow.ANY : startWindow;
        score = score == null ? ScoreFilter.ANY : score;
        related = related == null ? RelatedFilter.ANY : related;
        // Blanks dropped and the list made immutable here rather than at the edge, because a
        // repeated query parameter arrives as `?portal=a&portal=` from any form that renders
        // an empty option — and one blank name in the IN list matches nothing, so the filter
        // would silently return an empty page for a choice nobody made.
        portals = portals == null
                ? List.of()
                : portals.stream()
                        .filter(name -> name != null && !name.isBlank())
                        .map(String::trim)
                        .distinct()
                        .toList();
    }

    /**
     * The first page of everything, in the default order. The shape most tests want, and the
     * reason they do not each carry ten arguments.
     */
    public static ShortlistQuery first() {
        return new ShortlistQuery(
                null, null, null, false, null, null, null, null, false, false, null, null, DEFAULT_LIMIT);
    }

    public ShortlistQuery withTopic(String name) {
        return new ShortlistQuery(
                q,
                score,
                portals,
                archived,
                sort,
                startWindow,
                related,
                minMonths,
                deadlineOpen,
                possibleDuplicates,
                name,
                cursor,
                limit);
    }

    public ShortlistQuery withCursor(String next) {
        return new ShortlistQuery(
                q,
                score,
                portals,
                archived,
                sort,
                startWindow,
                related,
                minMonths,
                deadlineOpen,
                possibleDuplicates,
                topic,
                next,
                limit);
    }

    public ShortlistQuery withLimit(int rows) {
        return new ShortlistQuery(
                q,
                score,
                portals,
                archived,
                sort,
                startWindow,
                related,
                minMonths,
                deadlineOpen,
                possibleDuplicates,
                topic,
                cursor,
                rows);
    }

    public ShortlistQuery withSort(ShortlistSort order) {
        return new ShortlistQuery(
                q,
                score,
                portals,
                archived,
                order,
                startWindow,
                related,
                minMonths,
                deadlineOpen,
                possibleDuplicates,
                topic,
                cursor,
                limit);
    }

    public ShortlistQuery withScore(ScoreFilter filter) {
        return new ShortlistQuery(
                q,
                filter,
                portals,
                archived,
                sort,
                startWindow,
                related,
                minMonths,
                deadlineOpen,
                possibleDuplicates,
                topic,
                cursor,
                limit);
    }

    public ShortlistQuery withRelated(RelatedFilter filter) {
        return new ShortlistQuery(
                q,
                score,
                portals,
                archived,
                sort,
                startWindow,
                filter,
                minMonths,
                deadlineOpen,
                possibleDuplicates,
                topic,
                cursor,
                limit);
    }

    public ShortlistQuery withPortals(List<String> names) {
        return new ShortlistQuery(
                q,
                score,
                names,
                archived,
                sort,
                startWindow,
                related,
                minMonths,
                deadlineOpen,
                possibleDuplicates,
                topic,
                cursor,
                limit);
    }
}
