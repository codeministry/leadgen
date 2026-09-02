/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.analytics;

import java.time.LocalDate;
import java.util.List;

/**
 * How many offers arrived, per day, on the two dates an offer carries.
 *
 * <p><b>The bucket is a measurement; the breakdown inside it is not.</b> `ingested_at` is
 * written once and the upsert never touches it again, so an offer's arrival day survives
 * every re-read of a newsletter. `passed` and the four bands, by contrast, are rewritten on
 * every run — the filter re-judges the whole archive and the scorer overwrites its columns.
 * So the height of a bar is what happened; the colours in it are what today's rules say
 * about it. The screen has to say so, and the two halves of it sit under different
 * headings for that reason.
 *
 * @param byPublishedOn what the market advertised, clamped to a window — the date is parsed
 *     with a source-configured format, and one misparse in 1970 would otherwise stretch the
 *     axis over twenty thousand empty days.
 * @param withoutPublishedOn stated rather than dropped: a chart drawn from a field two
 *     thirds of the corpus does not carry has to say so.
 * @param byReceivedAt when the mail carrying the offer arrived. The axis that measures the
 *     market's own tempo: the ingest axis also counts how often the tool was run, and a
 *     truncated database moves every row to the moment it was refilled.
 * @param publishedOutOfRange the rows the clamp excluded. A date format that has drifted
 *     becomes visible here, and nowhere else.
 * @param withoutReceivedAt offers whose source is not a mail — a file dropped in by hand
 *     has no arrival date, and inventing one from its mtime would be the run's date in
 *     disguise.
 */
public record IntakeSeries(
        List<Day> byIngestedAt,
        List<Day> byPublishedOn,
        List<Day> byReceivedAt,
        int withoutPublishedOn,
        int publishedOutOfRange,
        int withoutReceivedAt) {

    /**
     * @param duplicates the same project through a second portal. Carried beside the
     *     primaries rather than left out, because how much of what arrives is a repeat is
     *     itself a fact about the market.
     */
    public record Day(
            LocalDate day,
            int primaries,
            int duplicates,
            int passed,
            int shortlisted,
            int review,
            int discarded,
            int unscored) {}
}
