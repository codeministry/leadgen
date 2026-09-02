/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.application;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

/**
 * What the operator is recording.
 *
 * @param status the only required field. Everything else is optional, because a
 *     correction usually changes one thing.
 * @param clearFollowUp distinguishes "leave the follow-up alone" from "remove it". A null
 *     date cannot say both, and a board where you can set a reminder but never cancel one
 *     is a board that fills up with dead reminders.
 *     <p>A {@code Boolean} rather than a {@code boolean}, and that is not a style choice:
 *     Jackson refuses to map an absent value into a primitive, so every request omitting
 *     the flag would be rejected as malformed — which is every request, since the point of
 *     a PATCH is that it names one thing.
 */
public record ApplicationUpdate(
        @NotNull ApplicationStatus status,
        LocalDate sentOn,
        LocalDate followUpOn,
        Boolean clearFollowUp,
        String outcome,
        String note) {

    public boolean clearsFollowUp() {
        return Boolean.TRUE.equals(clearFollowUp);
    }
}
