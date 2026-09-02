/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * One card on the pipeline board: the application, plus enough of the offer to
 * recognise it without a second request.
 *
 * @param followUpDue whether the follow-up date has passed. Computed here rather than in
 *     the browser, because "due" depends on the server's idea of today and two clients in
 *     two time zones must not disagree about it.
 */
public record ApplicationView(
        long id,
        long offerId,
        ApplicationStatus status,
        String title,
        String agency,
        String portal,
        String url,
        Integer scoreValue,
        BigDecimal rateEur,
        String packageDir,
        LocalDate sentOn,
        LocalDate followUpOn,
        boolean followUpDue,
        String outcome,
        String note,
        Instant updatedAt) {}
