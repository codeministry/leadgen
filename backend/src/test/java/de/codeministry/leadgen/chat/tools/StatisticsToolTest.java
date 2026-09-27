/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.chat.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.codeministry.leadgen.analytics.AnalyticsQueryService;
import de.codeministry.leadgen.analytics.AnalyticsSummary;
import de.codeministry.leadgen.analytics.AnalyticsSummaryQueryService;
import de.codeministry.leadgen.analytics.AnalyticsView;
import de.codeministry.leadgen.analytics.ApplicationAnalytics;
import de.codeministry.leadgen.analytics.IntakeSeries;
import de.codeministry.leadgen.analytics.LastRunQueryService;
import de.codeministry.leadgen.analytics.RunSeries;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Finding 5: the statistics tool on a database with pipeline runs but no intake yet — the
 * analytics view then has no day span at all, and the run days must neither break the tool nor
 * be dropped for lying outside a span that does not exist.
 */
class StatisticsToolTest {

    private static final LocalDate MONDAY = LocalDate.of(2026, 9, 21);

    private StatisticsTool tool;

    @BeforeEach
    void runsButNoIntake() {
        AnalyticsQueryService analytics = mock(AnalyticsQueryService.class);
        AnalyticsSummaryQueryService summary = mock(AnalyticsSummaryQueryService.class);
        LastRunQueryService lastRun = mock(LastRunQueryService.class);
        RunSeries runs = new RunSeries(
                List.of(
                        new RunSeries.Day(MONDAY, 1, 3, 2, 2, null),
                        new RunSeries.Day(MONDAY.plusDays(2), 2, 5, 4, 1, null)),
                List.of(),
                Instant.parse("2026-09-01T00:00:00Z"));
        when(analytics.analytics())
                .thenReturn(new AnalyticsView(
                        "Europe/Berlin",
                        Instant.now(),
                        null,
                        null,
                        null,
                        new IntakeSeries(List.of(), List.of(), List.of(), 0, 0, 0),
                        null,
                        null,
                        mock(ApplicationAnalytics.class),
                        runs,
                        List.of()));
        when(summary.summary()).thenReturn(mock(AnalyticsSummary.class));
        when(lastRun.lastRun()).thenReturn(Optional.empty());
        tool = new StatisticsTool(analytics, summary, lastRun);
    }

    @Test
    void withoutAWindowEveryRunDayIsKept() {
        StatisticsResult[] result = new StatisticsResult[1];
        assertThat(catchThrowable(() -> result[0] = tool.statistics(null, null)))
                .as("statistics over runs without intake")
                .isNull();
        assertThat(result[0].intake()).isEmpty();
        assertThat(result[0].runs()).extracting(RunSeries.Day::day).containsExactly(MONDAY, MONDAY.plusDays(2));
        assertThat(result[0].from()).isEqualTo(MONDAY);
        assertThat(result[0].to()).isEqualTo(MONDAY.plusDays(2));
    }

    @Test
    void anAskedWindowStillNarrowsTheRunDays() {
        StatisticsResult[] result = new StatisticsResult[1];
        assertThat(catchThrowable(() -> result[0] = tool.statistics("2026-09-22", null)))
                .as("statistics over runs without intake, from a day")
                .isNull();
        assertThat(result[0].runs()).extracting(RunSeries.Day::day).containsExactly(MONDAY.plusDays(2));
        assertThat(result[0].from()).isEqualTo(LocalDate.parse("2026-09-22"));
    }
}
