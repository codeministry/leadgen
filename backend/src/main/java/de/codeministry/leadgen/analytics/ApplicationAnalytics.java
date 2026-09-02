/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.analytics;

import de.codeministry.leadgen.application.ApplicationStatus;
import java.time.LocalDate;
import java.util.List;

/**
 * The half of the loop the tool cannot observe, as far as the operator has recorded it.
 *
 * <p>`application_event` records only real status changes, so `transitions` is the second
 * genuinely append-only series in this schema and needs no caveat at all.
 */
public record ApplicationAnalytics(
        List<StatusCount> byStatus, List<TransitionDay> transitions, ResponseMetrics response) {

    public record StatusCount(ApplicationStatus status, int applications) {}

    public record TransitionDay(LocalDate day, ApplicationStatus toStatus, int moves) {}

    /**
     * @param answered what the medians were computed over. Shipped beside them on purpose:
     *     a median over three applications is theatre, and the screen can only say so if it
     *     is told how many there were.
     * @param backdated a reply recorded before the send date. That is a data-entry fact and
     *     not a response time, so it is excluded from the medians and counted here rather
     *     than clamped to zero, where it would quietly pull the median down.
     * @param medianDaysToFirstReply null when nothing has been answered. Null rather than
     *     zero, because "no answer yet" and "answered the same day" are opposites.
     */
    public record ResponseMetrics(
            int sent,
            int answered,
            int backdated,
            Double medianDaysToFirstReply,
            Double p90DaysToFirstReply,
            int won,
            int lost,
            int rejected) {}
}
