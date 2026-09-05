/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.enrich;

/**
 * What one enrichment pass did.
 *
 * @param considered offers that passed the hard filter and were due for enrichment.
 * @param enriched offers whose ad was read and yielded at least one field.
 * @param incomplete offers left in the pipeline with a note. Never discarded — a portal
 *     having a bad afternoon must not cost a good project.
 * @param fromCache answered without a request. On a second run inside the TTL this equals
 *     `considered` and `requests` is zero, which is what ISC-47 asserts.
 * @param requests actual HTTP requests for ads, robots.txt excluded.
 * @param deferred offers the rate limiter turned away. Nothing was written for them, so
 *     they are due again on the next pass — which is the whole difference between this
 *     count and `incomplete`, and the reason it is reported rather than folded in.
 */
public record EnrichmentReport(
        int considered, int enriched, int incomplete, int fromCache, int requests, int deferred) {

    public static EnrichmentReport skipped() {
        return new EnrichmentReport(0, 0, 0, 0, 0, 0);
    }
}
