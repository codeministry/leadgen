/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.analytics;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Duration;
import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One timed stage of the run that is being read back, as {@code pipeline_stage} remembers it.
 *
 * <p>The same six values {@link StageTiming} carries while a run collects them, as the API
 * hands them over: where the time went, stage by stage, which is the question the counts on
 * the run row cannot answer. Mirrored by {@code core/model/last-run.ts}.
 *
 * @param position the order the stage ran in, from zero. The one thing the browser sorts by.
 * @param stage    the server's name for it, {@code DEDUPE} or {@code INGEST <source>}; English
 *                 on every screen, like a score reason.
 * @param status   {@code OK}, or {@code FAILED} with the reason in {@code note} or, on an OK row of a
 *                 model-bound stage, the width it ran at, {@code width=N}. A FAILED
 *                 source under a run that completed is normal; a FAILED stage last under a run
 *                 whose own status is FAILED is where that run stopped.
 * @param note     as {@code pipeline_stage} stores it, unchanged
 * @param width    the width an OK stage ran at, read out of a note that is exactly
 *                 {@code width=N}; null on a FAILED row, whose note is its reason, and on any
 *                 other note. Parsed once here so the browser never parses a note.
 */
public record LastRunStage(
        int position, String stage, Instant startedAt, Instant endedAt, String status, String note, Integer width) {

    private static final Pattern WIDTH = Pattern.compile("^width=(\\d+)$");

    /** A row as stored, with its width read out of the note. */
    public LastRunStage(int position, String stage, Instant startedAt, Instant endedAt, String status, String note) {
        this(position, stage, startedAt, endedAt, status, note, widthOf(status, note));
    }

    /**
     * The width in a note, only on an OK row. Too many digits for an int reads as no width, not as
     * an error: the note is a remark on the row, never a reason to refuse reading it.
     */
    static Integer widthOf(String status, String note) {
        if (!StageTiming.OK.equals(status) || note == null) {
            return null;
        }
        Matcher matcher = WIDTH.matcher(note);
        if (!matcher.matches()) {
            return null;
        }
        try {
            return Integer.valueOf(matcher.group(1));
        } catch (NumberFormatException tooWide) {
            return null;
        }
    }

    /**
     * How long the stage took. Derived here rather than in the browser, and
     * {@code @JsonProperty} for the same reason as {@link LastRunSource#complete()}: Jackson
     * serialises a record from its components, and an accessor is not one.
     */
    @JsonProperty("millis")
    public long millis() {
        return Duration.between(startedAt, endedAt).toMillis();
    }
}
