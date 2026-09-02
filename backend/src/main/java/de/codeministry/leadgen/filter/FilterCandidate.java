/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.filter;

import java.time.LocalDate;
import java.util.List;

/** The fields the hard filter reads. Everything else about an offer is irrelevant here. */
public record FilterCandidate(
        long id, String title, String description, String location, List<String> tags, LocalDate publishedOn) {}
