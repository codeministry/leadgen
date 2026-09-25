/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.answer;

/**
 * One judged factor and its points.
 *
 * @param factor one of {@code Judge.JUDGED}.
 * @param points the points as written.
 */
public record JudgedPoints(String factor, int points) {}
