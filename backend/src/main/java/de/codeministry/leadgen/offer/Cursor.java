/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.offer;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Where the last page stopped: the sort it was read under, and the row it ended on.
 *
 * <p><b>The sort is part of the cursor, and that is not belt and braces.</b> Without it a
 * cursor minted under {@code score} with a leading value of 88 and replayed under
 * {@code start} is read as epoch day 88 — March 1970 — and the row comparison then returns an
 * arbitrary slice with no error anywhere. Identical bytes, different meaning, silent. The
 * filters need no such guard: they narrow the set without redefining the key, so a cursor
 * replayed under different filters still walks the same order and merely returns fewer rows.
 * The sort is different in kind.
 *
 * <p>Microseconds on the timestamp, for the reason the shortlist's own note gives: Postgres
 * stores {@code timestamptz} to the microsecond and {@code now()} is the transaction's clock,
 * so every row an ingest batch writes carries the same value down to the microsecond.
 * Truncated to milliseconds the cursor names an instant <em>before</em> the row it came from,
 * and the next page's comparison then excludes the rest of that batch, silently.
 *
 * @param sort the sort this cursor was minted under
 * @param key  the last row's sort key, as {@link ShortlistSort.Key} carries it
 * @param at   the last row's {@code ingested_at}
 * @param id   the last row's id, which is what makes the tuple a total order
 */
record Cursor(ShortlistSort sort, long key, Instant at, long id) {

    private static final String SEPARATOR = "\\|";

    String encoded() {
        return "%s|%d|%d|%d".formatted(sort.key(), key, micros(at), id);
    }

    /**
     * The cursor as it came back, refused unless it was minted under the sort being asked for.
     *
     * <p>Three shapes end up here as a 400 rather than a 500: a mismatched sort, a cursor from
     * the three-part form this replaced — which is what a link somebody shared yesterday
     * carries — and a component that is not a number.
     */
    static Cursor parse(String raw, ShortlistSort sort) {
        String[] parts = raw.split(SEPARATOR);
        if (parts.length != 4) {
            throw new BadShortlistRequest(
                    "this cursor is not one this shortlist wrote; ask for the first page instead");
        }
        ShortlistSort minted = ShortlistSort.of(parts[0]);
        if (minted != sort) {
            throw new BadShortlistRequest(
                    "this cursor was minted for sort=%s and the request asks for sort=%s; the order decides what the cursor's key means, so it cannot be carried across"
                            .formatted(minted.key(), sort.key()));
        }
        try {
            return new Cursor(
                    minted, Long.parseLong(parts[1]), instantOf(Long.parseLong(parts[2])), Long.parseLong(parts[3]));
        } catch (NumberFormatException e) {
            throw new BadShortlistRequest(
                    "this cursor is not one this shortlist wrote; ask for the first page instead");
        }
    }

    /**
     * Lossless for anything Postgres can store; {@code toEpochMilli} is not.
     *
     * <p>Package-private rather than private because {@link ShortlistSort#FRESH} keys on the
     * very column this tiebreaker reads, so its key and this component are the same number.
     * A second conversion beside this one would be two roundings of one instant, and they
     * disagree exactly at a batch boundary — which is the failure this microsecond resolution
     * exists to prevent in the first place.
     */
    static long micros(Instant instant) {
        return instant.getEpochSecond() * 1_000_000L + instant.getNano() / 1_000L;
    }

    /**
     * The inverse, for binding a carried key back to a {@code timestamptz}.
     */
    static Instant instantOf(long micros) {
        return Instant.EPOCH.plus(micros, ChronoUnit.MICROS);
    }
}
