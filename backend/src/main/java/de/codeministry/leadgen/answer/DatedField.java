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
 * A phrase from the advert and the day it resolves to.
 *
 * @param text the phrase, or null.
 * @param date the day as {@code YYYY-MM-DD}, or null. A string rather than a date type, so
 *             the wire shape does not depend on how the web mapper is configured.
 */
public record DatedField(String text, String date) {}
