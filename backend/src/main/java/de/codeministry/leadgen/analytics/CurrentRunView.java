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
 * A pass that is still going.
 *
 * <p>Deliberately not a {@link LastRunView} with a flag. That one reports finished runs only,
 * and the reason is written on it: a {@code RUNNING} row carries zeros, so shown under the
 * heading "last run" it would claim a pass that found nothing. This answers the other
 * question — <em>is anything happening, and where is it</em> — and carries no counts at all,
 * so there is nothing for a reader to mistake for a result.
 *
 * <p>It exists because a pass takes eleven minutes on the deployed corpus and nothing on any
 * screen said so. The button answered 409 with a sentence nobody saw, and the dashboard went
 * on showing last night's numbers as though today's click had done nothing.
 *
 * @param stage         the stage the run is in, or null in the moment between opening the row
 *                      and entering the first stage. Names are the server's own — `DEDUPE`,
 *                      `ENRICH`, `INGEST <source>` — and stay English, like every other sentence
 *                      this API writes.
 * @param stagePosition 1-based, or null for the same moment.
 * @param stageTotal    how many stages this run will have. One per enabled source plus the
 *                      fixed ones, decided when the run started, because the configuration
 *                      can be reloaded underneath it.
 */
public record CurrentRunView(
    long id,
    Instant startedAt,
    String scoreModel,
    String stage,
    Integer stagePosition,
    Integer stageTotal,
    Instant stageStartedAt) {
}
