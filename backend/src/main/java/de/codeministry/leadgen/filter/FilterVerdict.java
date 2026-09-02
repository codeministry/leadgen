/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.filter;

/**
 * Why an offer was rejected, or that it was not.
 *
 * <p>A verdict always carries the stage. A number without a reason gets ignored within a
 * week, and that applies to a rejection at least as much as to a score.
 */
public record FilterVerdict(boolean passed, FilterStage stage, String reason) {

    private static final FilterVerdict ACCEPTED = new FilterVerdict(true, null, null);

    public static FilterVerdict accepted() {
        return ACCEPTED;
    }

    public static FilterVerdict rejected(FilterStage stage, String reason) {
        return new FilterVerdict(false, stage, reason);
    }
}
