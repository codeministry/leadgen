/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.mcp;

/** How many rows a list tool returns: the caller's limit, bounded, with a default when none is given. */
final class Rows {

    static final int MAX = 50;
    static final int DEFAULT = 20;

    private Rows() {}

    static int clamp(Integer limit, int fallback) {
        if (limit == null || limit <= 0) {
            return fallback;
        }
        return Math.min(limit, MAX);
    }
}
