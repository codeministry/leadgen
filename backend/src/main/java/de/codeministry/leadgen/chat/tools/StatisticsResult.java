/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.chat.tools;

import de.codeministry.leadgen.analytics.AnalyticsSummary;
import de.codeministry.leadgen.analytics.ApplicationAnalytics;
import de.codeministry.leadgen.analytics.IntakeSeries;
import de.codeministry.leadgen.analytics.RunSeries;
import de.codeministry.leadgen.analytics.ScoreDistribution;
import de.codeministry.leadgen.offer.FunnelView;
import java.time.LocalDate;
import java.util.List;

/**
 * The numbers the dashboard and the analytics screen show, as the statistics tool hands them to
 * the model: the screens' own records, never recomputed.
 *
 * <p><b>Which numbers the window narrows.</b> Only the two daily series, {@code intake} and
 * {@code runs}, and only by leaving days out. Everything else is a state rather than a history —
 * the funnel, the score histogram, the applications by status — and the screens show it whole, so
 * the tool does too; a windowed funnel would be a number no screen shows. {@code from} and {@code
 * to} are the window actually applied, which is the asked-for one clipped to the days the
 * analytics screen covers — its intake span widened by the run days, which can lie outside it —
 * so a model that asked for a year learns it got ninety days. Both are null only on a database
 * that has neither taken anything in nor run.
 *
 * @param zone          the zone the days are cut in, the analytics screen's own
 * @param from          first day of the window applied, inclusive
 * @param to            last day of the window applied, inclusive
 * @param funnel        what the hard filter removed, stage by stage, over the working list
 * @param intake        offers that came in per day inside the window, primaries and duplicates apart
 * @param runs          what the pipeline runs did per day inside the window
 * @param scores        the score histogram with the configured band lines
 * @param applications  applications per status
 * @param responses     sent, answered, won, lost, and how long answers took
 * @param scoreBands    the dashboard's four score bands
 * @param lastRunHealth the dashboard's line on the last run: when, which stage failed, mismatches
 * @param lastRun       the last recorded run's counters, or null when none was ever recorded
 */
public record StatisticsResult(
        String zone,
        LocalDate from,
        LocalDate to,
        FunnelView funnel,
        List<IntakeSeries.Day> intake,
        List<RunSeries.Day> runs,
        ScoreDistribution scores,
        List<ApplicationAnalytics.StatusCount> applications,
        ApplicationAnalytics.ResponseMetrics responses,
        AnalyticsSummary.ScoreBands scoreBands,
        AnalyticsSummary.RunHealth lastRunHealth,
        LastRunNumbers lastRun) {}
