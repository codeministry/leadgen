/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.config;

import java.time.LocalDate;

/**
 * When this source's numbers last moved.
 *
 * <p>{@code V9__source_run.sql} states the point of the table it comes from: <i>"One row per
 * source per run rather than a mutable last-run row, because the interesting question is when
 * the number changed, not what it is now."</i> This is that question, answered.
 *
 * <p><b>Data and not a sentence.</b> The catalog picks the wording and puts the numbers where
 * that language puts them; a server that sent "extracted has been 169 since 12 Sep" would send
 * it in English to a German screen. The comparison itself is the server's, through a
 * {@code lag()} window, because every number printed beside a list in this application is
 * counted by the server and a second implementation of "changed" in TypeScript is what that
 * rule exists to prevent.
 *
 * @param extractedChangedOn the most recent run where {@code extracted} differed from the run
 *                           before it, or null when it has been flat for the whole window
 * @param extractedBefore    what it was before that change
 * @param extractedNow       what it is now, or null when this source has never run
 * @param divergedOn         the most recent run where {@code announced} and {@code extracted}
 *                           disagreed, or null when they never have
 * @param missing            by how much, at that run
 * @param announcedStated    whether any run in the window carried a count at all. False is not
 *                           a failure: most sources state none, and saying so is the honest
 *                           alternative to an empty column.
 */
public record SourceTrend(
    LocalDate extractedChangedOn,
    Integer extractedBefore,
    Integer extractedNow,
    LocalDate divergedOn,
    Integer missing,
    boolean announcedStated) {
}
