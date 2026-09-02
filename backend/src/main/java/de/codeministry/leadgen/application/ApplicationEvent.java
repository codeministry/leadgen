/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.application;

import java.time.Instant;

/** One recorded change. The history a single mutable row cannot answer for. */
public record ApplicationEvent(
        ApplicationStatus fromStatus, ApplicationStatus toStatus, String note, Instant recordedAt) {}
