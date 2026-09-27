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
 * A duration phrase from the advert and the months it resolves to.
 *
 * @param text   the phrase, or null.
 * @param months whole months, or null.
 */
public record DurationField(String text, Integer months) {}
