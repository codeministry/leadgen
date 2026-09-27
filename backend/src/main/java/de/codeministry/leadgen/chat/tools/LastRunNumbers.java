/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.chat.tools;

import de.codeministry.leadgen.analytics.LastRunView;
import java.time.Instant;

/**
 * The last run's counters, copied from {@link LastRunView} and nothing added.
 *
 * <p>Not the view itself: it also carries the per-source and per-stage lists and the scoring
 * model's name, which are the ingest screen's diagnostics rather than answers to a question in
 * plain words, and a model name is configuration the chat has no reason to repeat.
 */
public record LastRunNumbers(
        Instant finishedAt,
        String status,
        int extracted,
        int written,
        int merged,
        int enriched,
        int filterPassed,
        int scored,
        int shortlisted,
        int review,
        int packaged) {

    static LastRunNumbers of(LastRunView run) {
        return new LastRunNumbers(
                run.finishedAt(),
                run.status(),
                run.extracted(),
                run.written(),
                run.merged(),
                run.enriched(),
                run.filterPassed(),
                run.scored(),
                run.shortlisted(),
                run.review(),
                run.packaged());
    }
}
