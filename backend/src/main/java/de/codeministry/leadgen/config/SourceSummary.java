/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.config;

import java.time.Instant;

/**
 * One row on the sources screen.
 *
 * <p><b>It no longer carries which layer defined it.</b> That value is computed once for the
 * whole file — the two layers override each other file by file and never key by key — and was
 * then stamped onto every row, where it asserted a per-row fact that cannot vary. It sits on
 * {@link SourcesView} now, which is the scope it is true at.
 *
 * @param announced what the documents of the last run stated about themselves, or null
 *                  when none of them says. A mismatch with {@code extracted} means the selectors have
 *                  drifted, which is the one failure that otherwise looks exactly like a quiet market.
 * @param survived  how many of this source's offers cleared the hard filter. Counted over
 *                  the whole archive rather than the last run, because that is what the number is worth
 *                  knowing for.
 */
public record SourceSummary(
        String id,
        String kind,
        boolean enabled,
        Instant lastRunAt,
        int documents,
        int extracted,
        Integer announced,
        int survived) {}
