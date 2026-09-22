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
 * <p>Which is why there is no {@code dir} parameter and never was: <b>a reverse is a named key
 * of its own.</b> {@link #DURATION_SHORT} is that, and it is the only one. "Lowest score
 * first" answers no question a shortlist asks — the band filter says "show me the weak ones"
 * far more precisely — and "latest start" is what {@code startWindow=later} already partitions,
 * inside which you still want soonest first.
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
 * <p>So every nullable key is wrapped in a {@code coalesce} whose sentinel puts "not stated"
 * last <b>under that key's own direction</b>. That is why the sentinel sits on the constant
 * and not on {@link Key}: the two duration sorts read one column in two directions, and a
 * sentinel of {@code -1} that puts the unstated last under DESC puts them <i>first</i> under
 * ASC. Held on the kind, adding the reverse would have silently inverted exactly the rows this
 * design exists to protect. What stays on {@link Key} is how the value binds back, because
 * that follows the column's type and never its direction.
 */
public enum ShortlistSort {

    /**
     * Highest first. What ships when nothing asks for anything else, and byte for byte the
     * order this screen had before sorting existed.
     */
    SCORE("score", "o.score_value", Direction.DESC, Key.NUMBER, Unstated.LOW),

    /**
     * Earliest first: which of these starts soonest.
     */
    START("start", "o.starts_on", Direction.ASC, Key.DAY, Unstated.LATE_DAY),

    /**
     * Nearest first: which of these has to be answered first.
     */
    DEADLINE("deadline", "o.apply_by", Direction.ASC, Key.DAY, Unstated.LATE_DAY),

    /**
     * Longest first: which of these pays longest.
     */
    DURATION("duration", "o.duration_months", Direction.DESC, Key.NUMBER, Unstated.LOW),

    /**
     * Shortest first: which of these fills a gap. The one reverse that earns a key of its own —
     * {@code minMonths} is a floor with no matching ceiling, so a short engagement is the one
     * thing no existing control can reach.
     */
    DURATION_SHORT("duration-asc", "o.duration_months", Direction.ASC, Key.NUMBER, Unstated.HIGH),

    /**
     * Newest first: what has come in since the last look.
     *
     * <p>The only key with nothing to fold to the end — {@code ingested_at} is written by the
     * upsert and is non-null by construction, so there is no {@code coalesce} and no sentinel.
     * It is also already the tiebreaker of every other tuple, so this sort names it twice:
     * {@code (ingested_at, ingested_at, id)} compares exactly as {@code (ingested_at, id)}
     * would, and keeping the tuple three-wide keeps one shape for the clause, the
     * {@code ORDER BY} and the cursor instead of a special case in all three.
     */
    FRESH("fresh", "o.ingested_at", Direction.DESC, Key.INSTANT, Unstated.NONE);

    private final String key;
    private final String column;
    private final Direction direction;
    private final Key kind;
    private final Unstated unstated;

    ShortlistSort(String key, String column, Direction direction, Key kind, Unstated unstated) {
        this.key = key;
        this.column = column;
        this.direction = direction;
        this.kind = kind;
        this.unstated = unstated;
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
                        .formatted(
                                name,
                                Arrays.stream(values()).map(ShortlistSort::key).collect(Collectors.joining(", ")))));
    }

    /**
     * A score or a month count as this sort's cursor carries it, "not stated" included.
     *
     * <p>On the sort and not on {@link Key}, because what a missing value has to become is the
     * sentinel of <i>this</i> sort: the same null is {@code -1} under {@link #DURATION} and a
     * high number under {@link #DURATION_SHORT}.
     */
    public long carried(Integer value) {
        return value == null ? unstated.carried : value;
    }

    /**
     * A day as this sort's cursor carries it, as an epoch day. Epoch day and not microseconds,
     * and this is the mirror of the note on the ingest timestamp: a {@code date} has no sub-day
     * component to lose, so the day number is lossless where truncating an instant is not.
     */
    public long carried(LocalDate value) {
        return value == null ? unstated.carried : value.toEpochDay();
    }

    /**
     * An instant as this sort's cursor carries it. No null branch, because the one sort keyed
     * on an instant is keyed on a column the upsert always writes.
     */
    public long carried(Instant value) {
        return Cursor.micros(value);
    }

    /**
     * The key expression, "not stated" folded onto the end of the list — or the bare column
     * where the key cannot be null.
     */
    String expression() {
        return unstated.literal == null ? column : "coalesce(%s, %s)".formatted(column, unstated.literal);
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
        return " AND (%s, o.ingested_at, o.id) %s (:cKey, :cAt, :cId)\n".formatted(expression(), direction.comparison);
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
     * What "not stated" is worth under a given direction: the SQL literal the {@code coalesce}
     * folds to, and the same value as the cursor carries it.
     *
     * <p>The two have to agree exactly or a page boundary falls in the wrong place for the one
     * row nobody has test data for, which is why they are one constant and not two literals.
     * Every value here is a compile-time constant of the enclosing class, so this nested enum
     * initialises without touching it — a sentinel read from a field declared further down
     * would be zero at exactly this moment.
     */
    private enum Unstated {

        /**
         * Below every real score and every real month count, so it sorts last under DESC.
         */
        LOW(String.valueOf(UNSTATED_LOW), UNSTATED_LOW),

        /**
         * Above every month count this application will store — {@code FieldExtractor} bounds
         * a duration to 120 — so it sorts last under ASC.
         */
        HIGH(String.valueOf(UNSTATED_HIGH), UNSTATED_HIGH),

        /**
         * Later than any day an advert states, so it sorts last under ASC.
         */
        LATE_DAY("DATE '" + UNSTATED_DAY + "'", LocalDate.parse(UNSTATED_DAY).toEpochDay()),

        /**
         * The column is non-null, so there is nothing to fold to the end and no literal to
         * wrap it in.
         */
        NONE(null, 0);

        private final String literal;
        private final long carried;

        Unstated(String literal, long carried) {
            this.literal = literal;
            this.carried = carried;
        }
    }

    /**
     * What a sort key is made of on the way back: how the cursor's long binds to the column it
     * is compared against.
     *
     * <p>Three kinds, and the kind is what keeps the SQL expression and the JDBC parameter in
     * agreement. A key bound as an {@code int} against a {@code date} expression is a cast
     * error at best, and at worst a comparison that answers.
     */
    public enum Key {

        /**
         * A score or a month count.
         */
        NUMBER {
            @Override
            Object bind(long carried) {
                return (int) carried;
            }
        },

        /**
         * A calendar day, carried as its epoch day.
         */
        DAY {
            @Override
            Object bind(long carried) {
                return java.sql.Date.valueOf(LocalDate.ofEpochDay(carried));
            }
        },

        /**
         * An ingest timestamp, carried as microseconds since the epoch — the same resolution
         * and the same conversion the cursor's own tiebreaker component uses, because on this
         * sort they are the same value.
         */
        INSTANT {
            @Override
            Object bind(long carried) {
                return java.sql.Timestamp.from(Cursor.instantOf(carried));
            }
        };

        abstract Object bind(long carried);
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
    static final int UNSTATED_LOW = -1;

    /**
     * Above every month count that can be stored, so it sorts last under ASC. A score never
     * needs it: no sort reads {@code score_value} upwards.
     */
    static final int UNSTATED_HIGH = 9999;
}
