/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.offer;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * How the shortlist is ordered, and therefore how its cursor is read.
 *
 * <p><b>An enum and not a validated string, because the type is the allowlist.</b> The wire
 * name arrives in a request and the SQL is composed from this side only, so nothing a caller
 * sends ever reaches a statement — the same reason the band thresholds are read from the
 * configuration rather than taken from the request.
 *
 * <h2>The order is the keyset</h2>
 *
 * <p>A page is walked with one row comparison, {@code (key, ingested_at, id) < (…)}, which is
 * a legal walk only while every column moves in the same direction. So the direction belongs
 * to the whole tuple and not to its leading column, and the consequence has to be written
 * down because it is invisible on screen: <b>under an ascending key the tiebreaker is
 * oldest-ingested first.</b> Mixing the directions would need either a decomposed OR-chain —
 * a second implementation of the ordering, free to drift from the {@code ORDER BY} — or a
 * negated key expression, which is unreadable in a diff and needs an index of its own.
 *
 * <h2>The sentinel, and why not NULLS LAST</h2>
 *
 * <p>{@code ORDER BY x DESC NULLS LAST} is expressible; the matching cursor comparison is
 * not. SQL row comparison yields NULL the moment any element is NULL, so a row whose key is
 * null is dropped by the {@code AND} — and a three-value cursor has no way to say "the last
 * row's key was null" either. The failure is the quiet kind: page one shows the unstated
 * offers at the end, correctly, and page two makes them evaporate while the match count still
 * counts them.
 *
 * <p>So every key is wrapped in a {@code coalesce} whose sentinel puts "not stated" last under
 * that key's direction. {@code coalesce(score_value, -1)} was already exactly this; the rest
 * is the general form of it. The sentinel is written once, in {@link Key}, because the
 * {@code ORDER BY}, the page clause and the cursor all have to agree on it and two literals
 * in two places disagree exactly once.
 */
public enum ShortlistSort {

    /**
     * Highest first. What ships when nothing asks for anything else, and byte for byte the
     * order this screen had before sorting existed.
     */
    SCORE("score", "o.score_value", Direction.DESC, Key.NUMBER),

    /**
     * Earliest first: which of these starts soonest.
     */
    START("start", "o.starts_on", Direction.ASC, Key.DAY),

    /**
     * Nearest first: which of these has to be answered first.
     */
    DEADLINE("deadline", "o.apply_by", Direction.ASC, Key.DAY),

    /**
     * Longest first: which of these pays longest.
     */
    DURATION("duration", "o.duration_months", Direction.DESC, Key.NUMBER);

    private final String key;
    private final String column;
    private final Direction direction;
    private final Key kind;

    ShortlistSort(String key, String column, Direction direction, Key kind) {
        this.key = key;
        this.column = column;
        this.direction = direction;
        this.kind = kind;
    }

    /**
     * The name this sort travels under, in the query string and in a cursor.
     */
    public String key() {
        return key;
    }

    public Key kind() {
        return kind;
    }

    /**
     * The sort named, or {@link #SCORE} when nothing was named.
     *
     * <p>Anything else is refused rather than defaulted: a control the server silently
     * ignores is worse than one that says it does not exist.
     */
    public static ShortlistSort of(String name) {
        if (name == null || name.isBlank()) {
            return SCORE;
        }
        return Arrays.stream(values())
            .filter(sort -> sort.key.equalsIgnoreCase(name.trim()))
            .findFirst()
            .orElseThrow(() -> new BadShortlistRequest("'%s' is not a sort this shortlist offers; it has %s"
                .formatted(name, Arrays.stream(values()).map(ShortlistSort::key).collect(Collectors.joining(", ")))));
    }

    /**
     * The key expression, "not stated" folded onto the end of the list.
     */
    String expression() {
        return "coalesce(%s, %s)".formatted(column, kind.sentinel);
    }

    /**
     * {@code coalesce(o.starts_on, DATE '9999-12-31') ASC, o.ingested_at ASC, o.id ASC}.
     */
    String orderBy() {
        String dir = direction.name();
        return "%s %s, o.ingested_at %s, o.id %s".formatted(expression(), dir, dir, dir);
    }

    /**
     * {@code AND (coalesce(…), o.ingested_at, o.id) > (:cKey, :cAt, :cId)}, derived from the
     * same expression the {@code ORDER BY} is, so the two cannot disagree.
     */
    String pageClause() {
        return " AND (%s, o.ingested_at, o.id) %s (:cKey, :cAt, :cId)\n"
            .formatted(expression(), direction.comparison);
    }

    private enum Direction {

        /**
         * Walks towards larger keys, so the rows after the cursor are the greater ones.
         */
        ASC(">"),

        /**
         * Walks towards smaller keys. This is what the score has always done.
         */
        DESC("<");

        private final String comparison;

        Direction(String comparison) {
            this.comparison = comparison;
        }
    }

    /**
     * What a sort key is made of: the sentinel that stands for "not stated", how the cursor
     * carries the value, and how it binds back.
     *
     * <p>Two kinds, and the pair is what keeps the SQL expression and the JDBC parameter in
     * agreement. A key bound as an {@code int} against a {@code date} expression is a cast
     * error at best, and at worst a comparison that answers.
     */
    public enum Key {

        /**
         * A score or a month count. Absent is {@link #UNSTATED_NUMBER}, which is below every
         * real value and is exactly what {@code coalesce(score_value, -1)} already did.
         */
        NUMBER(String.valueOf(UNSTATED_NUMBER)) {
            @Override
            Object bind(long carried) {
                return (int) carried;
            }
        },

        /**
         * A calendar day, carried as its epoch day. Epoch day and not microseconds, and this
         * is the mirror of the note on the ingest timestamp: a {@code date} has no sub-day
         * component to lose, so the day number is lossless where truncating an instant is not.
         */
        DAY("DATE '" + UNSTATED_DAY + "'") {
            @Override
            Object bind(long carried) {
                return java.sql.Date.valueOf(LocalDate.ofEpochDay(carried));
            }
        };

        private final String sentinel;

        Key(String sentinel) {
            this.sentinel = sentinel;
        }

        abstract Object bind(long carried);

        /**
         * A score or a month count as the cursor carries it.
         */
        public long of(Integer value) {
            return value == null ? UNSTATED_NUMBER : value;
        }

        /**
         * A day as the cursor carries it, "not stated" included.
         */
        public long of(LocalDate value) {
            return (value == null ? UNSTATED : value).toEpochDay();
        }
    }

    /**
     * Far enough out that no advert states it and inside what a {@code date} can hold.
     *
     * <p>Written once, here, and read by the two places that have to agree: the SQL
     * expression and the cursor's encoding of a null key. It is public for a third reason —
     * {@code FieldExtractor} bounds the dates it will store well below this day, so a stored
     * one can never sort among the offers that stated nothing, and the test that pins that
     * bound names this constant rather than a literal of its own.
     */
    public static final String UNSTATED_DAY = "9999-12-31";

    /**
     * Below every real score and every real month count, so it sorts last under DESC.
     */
    static final int UNSTATED_NUMBER = -1;

    static final LocalDate UNSTATED = LocalDate.parse(UNSTATED_DAY);
}
