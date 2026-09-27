/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.chat.tools;

import de.codeministry.leadgen.analytics.AnalyticsQueryService;
import de.codeministry.leadgen.analytics.AnalyticsSummaryQueryService;
import de.codeministry.leadgen.analytics.LastRunQueryService;
import java.time.LocalDate;
import java.util.function.Function;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * The dashboard's and the analytics screen's numbers, as a tool the chat model can call.
 *
 * <p><b>It counts nothing.</b> It calls {@link AnalyticsQueryService}, {@link
 * AnalyticsSummaryQueryService} and {@link LastRunQueryService}, the services behind {@code GET
 * /api/v1/analytics}, {@code /analytics/summary} and {@code /ingest/last}, and hands back their
 * records. A number in an answer is therefore the number on a screen by construction; a second
 * {@code count(*)} here would agree until the day one of the two was changed, and the chat exists
 * to not be that second opinion. {@code ChatToolsTest} holds it against the endpoints over HTTP.
 *
 * <p>The window only drops days from the two daily series, which is what the screen does when
 * it zooms; the reasoning is on {@link StatisticsResult}.
 *
 * <p>It never returns an offer's text, a source's configuration, a model name, or anything read
 * from a mail: the analytics records carry portal names and counts, and the last run is cut down
 * to its counters ({@link LastRunNumbers}).
 *
 * <p>Read-only: the three dependencies are read services.
 */
@Component
@RequiredArgsConstructor
public class StatisticsTool {

    private final AnalyticsQueryService analytics;
    private final AnalyticsSummaryQueryService summary;
    private final LastRunQueryService lastRun;

    @Tool(
            name = "statistics",
            description = "Returns the numbers the dashboard and analytics screens show: the filter funnel,"
                    + " offers that came in per day, pipeline runs per day, the score histogram and bands,"
                    + " applications per status with reply times, and the last run. 'from' and 'to' narrow"
                    + " only the per-day series; the result says which days were covered. Quote these"
                    + " numbers; never add them up into totals the result does not state.")
    public StatisticsResult statistics(
            @ToolParam(required = false, description = "First day to include, as YYYY-MM-DD.") String from,
            @ToolParam(required = false, description = "Last day to include, as YYYY-MM-DD.") String to) {
        var view = analytics.analytics();
        var dashboard = summary.summary();
        var runDays = view.runs().days();
        // The span both series cover. The intake's own span is null on a database that has run
        // but taken nothing in yet, and runs can lie outside it; either alone would drop days.
        LocalDate spanFrom = earliest(
                view.from(), runDays.isEmpty() ? null : runDays.getFirst().day());
        LocalDate spanTo =
                latest(view.to(), runDays.isEmpty() ? null : runDays.getLast().day());
        LocalDate first = latest(spanFrom, day(from, "from"));
        LocalDate last = earliest(spanTo, day(to, "to"));
        return new StatisticsResult(
                view.zone(),
                first,
                last,
                view.funnel(),
                within(view.intake().byIngestedAt().stream(), d -> d.day(), first, last),
                within(runDays.stream(), d -> d.day(), first, last),
                view.scores(),
                view.applications().byStatus(),
                view.applications().response(),
                dashboard.scoreBands(),
                dashboard.lastRun(),
                lastRun.lastRun().map(LastRunNumbers::of).orElse(null));
    }

    private static <T> java.util.List<T> within(
            Stream<T> days, Function<T, LocalDate> dayOf, LocalDate first, LocalDate last) {
        return days.filter(d -> (first == null || !dayOf.apply(d).isBefore(first))
                        && (last == null || !dayOf.apply(d).isAfter(last)))
                .toList();
    }

    private static LocalDate day(String text, String name) {
        return Days.parse(text, name);
    }

    /** The later of two days, either of which may be missing; null only when both are. */
    private static LocalDate latest(LocalDate a, LocalDate b) {
        return a == null || (b != null && b.isAfter(a)) ? b : a;
    }

    /** The earlier of two days, either of which may be missing; null only when both are. */
    private static LocalDate earliest(LocalDate a, LocalDate b) {
        return a == null || (b != null && b.isBefore(a)) ? b : a;
    }
}
