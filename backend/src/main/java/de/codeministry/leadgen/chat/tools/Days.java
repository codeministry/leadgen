/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.chat.tools;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * A day as a tool's argument: {@code YYYY-MM-DD}, or nothing.
 *
 * <p>One parse and one refusal for every tool that takes a day, so the model reads the same
 * sentence whichever tool it got a date wrong in. An unreadable day is refused rather than dropped,
 * because a dropped bound widens the answer under a question that narrowed it.
 */
final class Days {

    private Days() {}

    /**
     * @param text the argument as the model wrote it; blank or null is no bound.
     * @param name the argument's name, for the refusal.
     * @return the day, or null when none was given.
     * @throws IllegalArgumentException with a sentence the model can act on, when the text is no day.
     */
    static LocalDate parse(String text, String name) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(text.strip());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(name + " must be a day written as YYYY-MM-DD, not '" + text + "'");
        }
    }
}
