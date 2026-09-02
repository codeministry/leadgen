/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.filter;

import java.util.Map;

/**
 * What one filter pass did.
 *
 * @param removed how many offers each stage rejected. Only stages that rejected
 *     something appear.
 * @param passed how many survived every stage.
 * @param considered every offer judged. `removed` summed plus `passed` equals this, and
 *     ISC-42 exists because a stage silently dropping an offer without counting it is
 *     exactly the failure a total alone cannot show.
 */
public record FilterReport(Map<FilterStage, Integer> removed, int passed, int considered) {

    public int removedTotal() {
        return removed.values().stream().mapToInt(Integer::intValue).sum();
    }

    /** The share that survives, which is the daily language-model budget. */
    public double passRate() {
        return considered == 0 ? 0 : (double) passed / considered;
    }
}
