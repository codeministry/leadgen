/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.enrich;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * What one enrichment pass did.
 *
 * @param considered offers that passed the hard filter and were due for enrichment.
 * @param enriched   offers whose ad was read and yielded at least one field.
 * @param incomplete offers left in the pipeline with a note. Never discarded — a portal
 *                   having a bad afternoon must not cost a good project.
 * @param fromCache  answered without a request. On a second run inside the TTL this equals
 *                   `considered` and `requests` is zero, which is what ISC-47 asserts.
 * @param requests   actual HTTP requests for ads, robots.txt excluded.
 * @param deferred   offers the rate limiter turned away. Nothing was written for them, so
 *                   they are due again on the next pass — which is the whole difference between this
 *                   count and `incomplete`, and the reason it is reported rather than folded in.
 * @param width      the width the stage's bounded loop actually ran at: {@code 1} when it ran
 *                   sequentially, was skipped, had nothing due or no model to ask. The
 *                   {@code pipeline_stage} note and the {@code " at width N"} of the log line
 *                   are both read off this one value. Not part of the JSON a run answers with:
 *                   a response field is part of the API, and this one is already on the stage
 *                   row.
 */
public record EnrichmentReport(
        int considered,
        int enriched,
        int incomplete,
        int fromCache,
        int requests,
        int deferred,
        @JsonIgnore int width) {

    /** At width 1, which is what every caller outside the stage's own run means. */
    public EnrichmentReport(int considered, int enriched, int incomplete, int fromCache, int requests, int deferred) {
        this(considered, enriched, incomplete, fromCache, requests, deferred, 1);
    }

    public static EnrichmentReport skipped() {
        return new EnrichmentReport(0, 0, 0, 0, 0, 0);
    }
}
