/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.application;

/**
 * What the stage that puts offers on the board did.
 *
 * @param standing how many offers the shortlist holds that are eligible for a card at all.
 *                 The standing total rather than this run's additions, for the same reason
 *                 {@code IngestReport.merged} is one: a second run in the same morning opens
 *                 nothing, and a zero beside it would read as "the shortlist stopped working".
 * @param opened   how many of them got an application row on this pass. Normally the offers
 *                 that were scored a minute earlier, and zero on a re-run.
 */
public record OpenReport(int standing, int opened) {

    public static OpenReport nothing() {
        return new OpenReport(0, 0);
    }
}
