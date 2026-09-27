/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.chat.tools;

import de.codeministry.leadgen.analytics.IntakeSeries;
import de.codeministry.leadgen.analytics.MarketView;
import de.codeministry.leadgen.analytics.RunSeries;
import java.util.List;

/**
 * One window's daily series added up, so the model quotes a total instead of summing days, and
 * what two windows' totals differ by, subtracted here rather than by the model.
 *
 * <p>Every field is a sum over the days the analytics screen shows for that window, nothing
 * counted anew: {@code ChatToolsTest} adds the endpoint's own days and compares.
 *
 * @param primaries   offers that came in, duplicates not counted
 * @param duplicates  copies of an offer already known
 * @param passed      of the primaries, those that survived the hard filter
 * @param shortlisted of the primaries, those whose band is shortlisted today
 * @param review      of the primaries, those in the review band today
 * @param discarded   of the primaries, those in the discarded band today
 * @param unscored    of the primaries, those without a score
 * @param runs        pipeline runs
 * @param knockouts   offers the hard filter removed, all stages together
 */
public record WindowTotals(
        int primaries,
        int duplicates,
        int passed,
        int shortlisted,
        int review,
        int discarded,
        int unscored,
        int runs,
        int knockouts) {

    /** The totals of one window's three series. */
    static WindowTotals of(List<IntakeSeries.Day> intake, List<RunSeries.Day> runs, List<MarketView.StageDay> stages) {
        return new WindowTotals(
                intake.stream().mapToInt(IntakeSeries.Day::primaries).sum(),
                intake.stream().mapToInt(IntakeSeries.Day::duplicates).sum(),
                intake.stream().mapToInt(IntakeSeries.Day::passed).sum(),
                intake.stream().mapToInt(IntakeSeries.Day::shortlisted).sum(),
                intake.stream().mapToInt(IntakeSeries.Day::review).sum(),
                intake.stream().mapToInt(IntakeSeries.Day::discarded).sum(),
                intake.stream().mapToInt(IntakeSeries.Day::unscored).sum(),
                runs.stream().mapToInt(RunSeries.Day::runs).sum(),
                stages.stream().mapToInt(MarketView.StageDay::removed).sum());
    }

    /** This window's totals minus the other's, field by field: positive means more here. */
    WindowTotals minus(WindowTotals other) {
        return new WindowTotals(
                primaries - other.primaries,
                duplicates - other.duplicates,
                passed - other.passed,
                shortlisted - other.shortlisted,
                review - other.review,
                discarded - other.discarded,
                unscored - other.unscored,
                runs - other.runs,
                knockouts - other.knockouts);
    }
}
