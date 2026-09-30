/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
/**
 * The questions the empty chat offers, found by rule before any model phrases them (ISC-455).
 *
 * <p>Each {@link de.codeministry.leadgen.chat.suggest.SuggestionTrigger} reads the read service
 * behind a screen, so a suggested number is the number that screen shows. A trigger whose line in
 * {@code chat.suggestions.*} is not met offers nothing; the two evergreen ones always offer one.
 * Nothing in this package writes, and nothing here calls a model: phrasing is ISC-456's.
 */
package de.codeministry.leadgen.chat.suggest;
