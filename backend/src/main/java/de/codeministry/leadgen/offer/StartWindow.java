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
 * When an engagement starts, as a filter.
 *
 * <p>Four values that <b>partition the working set</b>, and the fourth one is the whole
 * point. The three dated windows all carry {@code IS NOT NULL}, so without {@link #UNKNOWN}
 * their union is not the unfiltered list — and {@code starts_on} is set only where the advert
 * named a day the extractor could resolve, which is a minority. Any window would then hide
 * most of the shortlist invisibly, because a short list after filtering looks exactly like a
 * filter that worked. Selectable, it is instead a property a test can assert: the four counts
 * add up to the unfiltered match.
 *
 * <p>Same family as {@code remote.accept_unknown}, which is rendered, validated and read by
 * nothing — a filter that cannot express what most of the data actually is.
 *
 * <p>An enum and not a string like {@code band}: an unrecognised band quietly means "all",
 * which is a defensible reading of a range, while an unrecognised window quietly breaks the
 * partition this exists for.
 *
 * <p>{@code current_date} is the server's, never a date the browser sends. Two readers in two
 * timezones must not get two lists, and a date in a request is a date that can be edited.
 */
public enum StartWindow {

    /**
     * No filter at all.
     */
    ANY("any", ""),

    /**
     * The date has arrived, or passed. A date already in the past belongs here and not
     * nowhere: a resolved "ab sofort" from last week is the most immediate thing on the list.
     */
    NOW("now", " AND o.starts_on IS NOT NULL AND o.starts_on <= current_date\n"),

    /**
     * Within the next thirty days, the boundary day included.
     *
     * <p>The thirty is a literal in the clause and the wire value is {@code soon}, not
     * {@code 30d}, so the window can be retuned to fourteen without invalidating every saved
     * link — and the catalog key that labels it is the other half of that same edit. It is
     * deliberately not a configured threshold the way the band boundaries are: those appear
     * on the rules screen and in the histogram and therefore have to come from
     * {@code ConfigRegistry}, while this number is the definition of the filter's own name
     * and appears nowhere else.
     */
    SOON("soon", " AND o.starts_on IS NOT NULL AND o.starts_on > current_date"
        + " AND o.starts_on <= current_date + 30\n"),

    /**
     * Further out than that.
     */
    LATER("later", " AND o.starts_on IS NOT NULL AND o.starts_on > current_date + 30\n"),

    /**
     * The advert said nothing a day could be read out of. {@code start_text} is never matched
     * here: a filter that sometimes reads a phrase and sometimes a date is two filters.
     */
    UNKNOWN("unknown", " AND o.starts_on IS NULL\n");

    private final String key;
    private final String clause;

    StartWindow(String key, String clause) {
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
     * The window named, or {@link #ANY} when nothing was named. Anything else is refused,
     * for the same reason an unknown sort is.
     */
    public static StartWindow of(String name) {
        if (name == null || name.isBlank() || ANY.key.equalsIgnoreCase(name.trim())) {
            return ANY;
        }
        return Arrays.stream(values())
            .filter(window -> window.key.equalsIgnoreCase(name.trim()))
            .findFirst()
            .orElseThrow(() -> new BadShortlistRequest("'%s' is not a start window; it has %s".formatted(
                name, Arrays.stream(values()).map(StartWindow::key).collect(Collectors.joining(", ")))));
    }
}
