/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.chat.suggest;

/**
 * One question the empty chat offers: the trigger that found it, the sentence in the reader's
 * language — the model's phrasing or the catalog's own — and the number behind it.
 */
public record Suggestion(String trigger, String text, int count) {}
