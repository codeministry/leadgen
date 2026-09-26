/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.web;

import de.codeministry.leadgen.analytics.AnalyticsSummary;
import de.codeministry.leadgen.analytics.AnalyticsSummaryQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The dashboard's one read, slimmer than the analytics screen's.
 *
 * <p>No query parameters, like every other read endpoint here and like
 * {@link AnalyticsController} beside it. Three groups only: a fixed fourteen-day intake
 * trend, the archive's score bands, and the health of the last recorded run — everything a
 * dashboard card needs and nothing the full {@code /api/v1/analytics} payload carries beyond
 * it.
 */
@RestController
@RequestMapping("/api/v1/analytics/summary")
@RequiredArgsConstructor
class AnalyticsSummaryController {

    private final AnalyticsSummaryQueryService summary;

    @GetMapping
    AnalyticsSummary summary() {
        return summary.summary();
    }
}
