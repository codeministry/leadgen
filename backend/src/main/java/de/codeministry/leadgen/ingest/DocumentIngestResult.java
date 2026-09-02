/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.ingest;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One document's contribution to a run.
 *
 * @param announced what the document said it contained, when it says so at all — the
 *     newsletter names the number in its subject line. Null when the source declares no
 *     `expect_count_from_subject`.
 */
public record DocumentIngestResult(String documentId, int extracted, Integer announced) {

    /**
     * False only when the document stated a count and the extraction missed it.
     *
     * <p><b>{@code @JsonProperty} is load-bearing.</b> Jackson serialises a record from its
     * components, and a derived accessor is not one — without the annotation this method
     * exists in Java and not in the JSON. The browser then read {@code undefined} for every
     * document, {@code !complete} was true for every document, and the dashboard's mismatch
     * alert fired on every healthy run that announced a count: five documents, five
     * "extracted fewer offers than announced" on a run where all five matched exactly.
     * Measured against the demo corpus on 2026-09-02. The one check nothing else can make
     * is worth nothing if it cries on every run.
     */
    @JsonProperty("complete")
    public boolean complete() {
        return announced == null || announced == extracted;
    }
}
