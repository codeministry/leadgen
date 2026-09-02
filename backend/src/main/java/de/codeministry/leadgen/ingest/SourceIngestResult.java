/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.ingest;

import java.util.List;

/**
 * One source's contribution to a run.
 *
 * @param written rows the upsert touched, insert or update alike — not new rows.
 *     Re-reading the same mail writes every offer again and adds none.
 */
public record SourceIngestResult(
        String sourceId, int documents, int extracted, int written, List<DocumentIngestResult> details) {}
