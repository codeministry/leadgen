/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */

/**
 * The record of what the runs did, and the charts read from it.
 *
 * <p>This is the only append-only part of the application, and it has to be: every pipeline
 * stage overwrites its own columns on the next pass. {@code FilterService} re-judges every
 * offer and rewrites {@code filter_stage}, {@code ScoringService} deletes and reinserts the
 * reasons. What a run did is therefore destroyed by the next one unless it is written down
 * at the moment it is still true.
 *
 * <p>{@code pipeline_run} answers "what did this run do"; {@code source_run} answers the same
 * one level down, per source and per document, and carries the announced-versus-extracted
 * comparison that nothing else can make. Neither table is computable from the other, and
 * neither is computable from the offers.
 *
 * <p>The queries here deliberately do <b>not</b> apply the working-set predicate the
 * shortlist uses. This is the record of what the market did, not today's list; excluding the
 * archive would empty every chart older than the window. That these numbers differ from the
 * shortlist's is correct.
 */
package de.codeministry.leadgen.analytics;
