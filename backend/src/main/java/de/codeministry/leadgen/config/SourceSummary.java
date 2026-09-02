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
 * @param layer which of the two configuration layers this source's definition came from.
 *     It is the thing that explains a surprising run: a source behaving unexpectedly is
 *     usually one whose external file is overriding the shipped default, or one whose
 *     external file is missing so the default applies.
 * @param announced what the documents of the last run stated about themselves, or null
 *     when none of them says. A mismatch with {@code extracted} means the selectors have
 *     drifted, which is the one failure that otherwise looks exactly like a quiet market.
 * @param survived how many of this source's offers cleared the hard filter. Counted over
 *     the whole archive rather than the last run, because that is what the number is worth
 *     knowing for.
 */
public record SourceSummary(
        String id,
        String kind,
        boolean enabled,
        String layer,
        Instant lastRunAt,
        int documents,
        int extracted,
        Integer announced,
        int survived) {}
