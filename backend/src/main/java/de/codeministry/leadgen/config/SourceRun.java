/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.config;

import java.time.LocalDate;

/**
 * What one source did on one run.
 *
 * <p><b>A day and not an instant.</b> The time of day a mailbox is read is the operator's
 * working hours, it is on a screen whose screenshots get published, and no question this panel
 * answers needs it. The date is cut in SQL, by the server's own clock, the way every other date
 * comparison in this application is.
 *
 * @param documents how many documents the connector handed over
 * @param extracted how many offers were read out of them
 * @param written   how many rows that became. <b>Shown for the first time here:</b> it has been
 *                  recorded on every run since {@code V9} and displayed nowhere, and the gap
 *                  between it and {@code extracted} is what re-reading a newsletter looks like —
 *                  1289 extracted became 1280 rows, because nine listings appeared in two mails.
 * @param announced what the documents said they held, or null where they say nothing. The one
 *                  check nothing else can make: a selector that stops matching loses offers, and
 *                  fewer offers is indistinguishable from a quiet day on the market.
 */
public record SourceRun(LocalDate ranOn, int documents, int extracted, int written, Integer announced) {

    /**
     * How far {@code announced} and {@code extracted} are apart, or null where there is no
     * count to check against. Computed here rather than in the browser, so the badge in the
     * history and the badge in the table row are the same number from the same place.
     */
    public Integer missing() {
        return announced == null ? null : announced - extracted;
    }
}
