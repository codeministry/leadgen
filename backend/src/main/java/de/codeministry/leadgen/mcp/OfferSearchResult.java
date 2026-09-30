/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.mcp;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** One page of {@code leadgen_search_offers}: the rows, how many matched, and where the next page starts. */
@JsonInclude(JsonInclude.Include.NON_NULL)
record OfferSearchResult(
        List<OfferSummary> offers, int returned, int matched, int total, String nextCursor, Boolean truncated) {}
