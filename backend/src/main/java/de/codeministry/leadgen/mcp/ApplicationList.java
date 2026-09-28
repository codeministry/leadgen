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

/** {@code leadgen_list_applications}: the rows, how many matched the status, and whether the cap cut them. */
@JsonInclude(JsonInclude.Include.NON_NULL)
record ApplicationList(List<ApplicationRow> applications, int returned, int matched, Boolean truncated) {}
