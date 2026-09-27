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

/**
 * The run before the last one, as much of it as a comparison needs.
 *
 * <p>Only the figures and the two instants: the run status sheet puts a small delta beside each
 * of the last run's numbers and its duration, and that is all this answers. The previous run's
 * sources, stages and removals are not repeated — a second full {@link LastRunView} nested in
 * the first would be a history endpoint that nobody asked for, and the analytics screen already
 * has one.
 *
 * <p>Chosen by the same rule as the last run itself — by {@code started_at}, finished, never
 * {@code ABANDONED} — so "the one before" cannot be a row the last run's own query would have
 * skipped.
 */
public record LastRunPrevious(
        Instant startedAt,
        Instant finishedAt,
        String status,
        int extracted,
        int written,
        int merged,
        int enriched,
        int filterConsidered,
        int filterPassed,
        int scored,
        int shortlisted,
        int review,
        int packaged) {}
