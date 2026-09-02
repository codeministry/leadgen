/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.analytics;

import de.codeministry.leadgen.offer.FunnelView;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Everything the analytics screen draws, in one answer.
 *
 * <p>One payload rather than six endpoints, because the screen shows one moment. Six
 * requests would be six failure modes and six loading states, and a run finishing between
 * the second and the fifth would leave a funnel that does not match a histogram with
 * nothing on the page saying why. The house already bundles for this reason: an
 * `IngestReport` carries five sub-reports and a `ShortlistEntry` carries four parts.
 *
 * <p>No query parameters, for the same reason `/api/offers` has none: the daily buckets for
 * both axes come down together and the browser switches between them, aggregates them into
 * weeks and windows them. A parameter would be a second implementation of an aggregation
 * that already exists in SQL, disagreeing with it the first time either changed. The one
 * thing the browser cannot re-bucket is a median, which is why the response metrics arrive
 * as scalars.
 *
 * @param zone the timezone the day boundaries were cut on. A `date_trunc` over a
 *     `timestamptz` uses the session timezone, so two readers in two zones would bucket
 *     differently — the same reason a follow-up is decided to be due on the server.
 * @param funnel reused as it stands. It already carries the stage ids, their labels and
 *     their order, and nothing in the browser may hold a second copy of that list.
 * @param scales which rulesets and judges the archive's scores were produced under.
 */
public record AnalyticsView(
        String zone,
        Instant generatedAt,
        LocalDate from,
        LocalDate to,
        FunnelView funnel,
        IntakeSeries intake,
        MarketView market,
        ScoreDistribution scores,
        ApplicationAnalytics applications,
        RunSeries runs,
        List<ScaleInUse> scales) {}
