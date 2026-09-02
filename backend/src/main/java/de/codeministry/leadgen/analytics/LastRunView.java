/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.analytics;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The last run as the database remembers it — <b>not</b> an {@code IngestReport}.
 *
 * <p>It exists because the dashboard used to know about a run only if the browser itself
 * had started one. Measured on 2026-09-02: a {@code pipeline_run} row six minutes old,
 * and the screen said "No run yet". After a scheduled pass that is every morning, so the
 * one screen meant to answer "what came in this morning" answered "nothing has ever
 * happened".
 *
 * <p><b>Two things are deliberately missing rather than reconstructed.</b> Both are
 * genuinely not persisted, and inventing a plausible value for either would make a
 * historical row look like a live report:
 *
 * <ul>
 *   <li><b>The per-document {@code announced} detail.</b> {@code source_run} holds one row
 *       per source per run, so the mismatch can be reported per source but not per
 *       document. The badges naming the document belong to a run this browser watched.
 *   <li><b>The digest path.</b> {@code pipeline_run} records only whether a digest was
 *       written. The file is on the machine that ran, and a path guessed from the current
 *       configuration would point at wherever the configuration points <i>today</i>.
 * </ul>
 *
 * @param finishedAt when the run ended. The reason this record is worth having at all: it
 *     is what tells the reader whether they are looking at tonight's pass or at their own
 *     click.
 * @param status {@code COMPLETE}, or {@code AWAITING_BATCH} while the scores of a batched
 *     run are still in flight — in which case the packaging and the digest have not
 *     happened yet and the counts below say so.
 * @param scoreModel which judge produced the scores. A run without its scale is a number
 *     with nothing behind it, and two runs under two models are not comparable.
 * @param merged the standing total inside the deduplication window, exactly as
 *     {@code IngestReport.merged} is. A second run moves nothing, and a zero here would
 *     read as "deduplication stopped working".
 * @param removed offers rejected per hard-filter stage, keyed by the stage name. Only
 *     stages that rejected something appear, and the enum is not restated here: it has
 *     grown once already.
 * @param sources what each source contributed, ordered by name.
 */
public record LastRunView(
        Instant finishedAt,
        String status,
        String scoreModel,
        int extracted,
        int written,
        int merged,
        Map<String, Integer> removed,
        int filterConsidered,
        int filterPassed,
        int scored,
        int shortlisted,
        int review,
        int packaged,
        boolean digestWritten,
        List<LastRunSource> sources) {}
