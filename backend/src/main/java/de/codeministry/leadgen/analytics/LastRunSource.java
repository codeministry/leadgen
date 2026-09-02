/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.analytics;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One source's contribution to the run that is being read back.
 *
 * <p>The same four numbers {@code SourceIngestResult} carries, minus the per-document
 * breakdown — {@code source_run} stores one row per source per run and not one per
 * document, so the detail genuinely does not exist here. See {@link LastRunView} for why
 * that absence is left visible rather than filled in.
 *
 * @param announced what the documents of this source said they contained, summed. Null
 *     when the source states no count to check against, which is most of them. The
 *     comparison between announced and extracted is the one check nothing else can make: a
 *     selector that stops matching loses offers, and fewer offers is indistinguishable
 *     from a quiet day on the market.
 * @param written rows the upsert touched, insert or update alike — not new rows.
 */
public record LastRunSource(String sourceId, int documents, int extracted, int written, Integer announced) {

    /**
     * False only when the source stated a count and the extraction missed it.
     *
     * <p>{@code @JsonProperty} for the same reason as
     * {@link de.codeministry.leadgen.ingest.DocumentIngestResult#complete()}: Jackson
     * serialises a record from its components, and a derived accessor is not one. Without
     * it the field is absent, the browser reads {@code undefined}, and the warning fires on
     * every source that announced anything at all.
     */
    @JsonProperty("complete")
    public boolean complete() {
        return announced == null || announced == extracted;
    }
}
