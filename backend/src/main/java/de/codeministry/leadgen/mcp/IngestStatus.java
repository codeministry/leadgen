/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.mcp;

import de.codeministry.leadgen.analytics.LastRunView;

/**
 * {@code leadgen_ingest_status}: the last finished run, and the one in flight or the sentence saying
 * there is none. {@code current} is either a run or that sentence, as codeministry-mcp answered it.
 */
record IngestStatus(LastRunView last, Object current) {

    static final String NO_RUN = "no run in flight";
}
