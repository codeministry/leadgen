/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */

/**
 * The pipeline orchestrator, and the types a run reports itself with.
 *
 * <p>{@code IngestService.run} is the order of the whole application, and the order is
 * load-bearing: the model is checked before anything is fetched, deduplication runs after all
 * sources rather than per source, the archive runs after the filter and before the two stages
 * that cost money, and the run is recorded last by something that cannot throw.
 *
 * <p><b>One failing source must not end the run.</b> Failures are caught per source, so an
 * unreachable mailbox does not stop the file sources behind it.
 *
 * <p>The counts a report carries do not all mean the same thing, and the difference matters:
 * {@code merged} is a standing total, because a second run moves nothing and a zero would read
 * as "deduplication stopped working", while {@code scored} counts this run, because a run that
 * judges only what changed legitimately judges nothing.
 */
package de.codeministry.leadgen.ingest;
