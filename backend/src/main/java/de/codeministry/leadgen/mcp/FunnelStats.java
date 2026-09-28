/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.mcp;

import de.codeministry.leadgen.application.ApplicationStatus;
import de.codeministry.leadgen.offer.FunnelView;
import java.time.Instant;
import java.util.List;

/** The three answers {@code leadgen_funnel_stats} gives: the overview, one analytics section, or why none. */
sealed interface FunnelStats {

    /** No section asked: the funnel stages and the application lanes. */
    record Overview(FunnelView funnel, List<ApplicationStatus.Lane> lanes) implements FunnelStats {}

    /** One slice of the analytics view, with the moment the view was computed. */
    record Section(String section, Instant generatedAt, Object data) implements FunnelStats {}

    /** A section name the analytics view does not have, with the ones it does. */
    record Unknown(String error, List<String> available) implements FunnelStats {}
}
