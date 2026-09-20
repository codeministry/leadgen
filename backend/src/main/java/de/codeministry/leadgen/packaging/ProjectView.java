/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.packaging;

import de.codeministry.leadgen.config.model.SkillProfile;

import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * One reference project as a letter sees it: already in the letter's language.
 *
 * <p>The templates used to branch on the language themselves — {@code pitchDe} in the German
 * one, {@code pitchEn!pitchDe} in the English one — and the title could not branch at all,
 * because there was only one. So a German letter printed English titles and an English letter
 * printed German pitches under them. Choosing here instead makes both templates identical and
 * leaves exactly one place where "which language" is decided.
 *
 * @param title  the title in the letter's language, with the other one as a fallback
 * @param period the rendered period, or null when the profile states neither end
 * @param pitch  the pitch in the letter's language, with the other one as a fallback
 */
public record ProjectView(String title, String period, String pitch) {

    /**
     * {@code MM/yyyy} and not a localised month name: a German and an English reader read
     * this identically, and a month is the finest resolution the profile actually states.
     */
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MM/yyyy", Locale.ROOT);

    private static final String EN = "en";

    static ProjectView of(SkillProfile.ReferenceProject project, String language) {
        return new ProjectView(
            title(project, language),
            period(project.from(), project.to(), language),
            pitch(project, language));
    }

    static String title(SkillProfile.ReferenceProject project, String language) {
        return EN.equalsIgnoreCase(language)
            ? firstOf(project.titleEn(), project.titleDe())
            : firstOf(project.titleDe(), project.titleEn());
    }

    static String pitch(SkillProfile.ReferenceProject project, String language) {
        return EN.equalsIgnoreCase(language)
            ? firstOf(project.pitchEn(), project.pitchDe())
            : firstOf(project.pitchDe(), project.pitchEn());
    }

    /**
     * An open end is the common case — most of these projects are still running — so it is
     * the one shape that needs a word, and the word is the only part that is translated.
     */
    static String period(YearMonth from, YearMonth to, String language) {
        if (from == null) {
            return to == null ? null : MONTH.format(to);
        }
        if (to == null) {
            return (EN.equalsIgnoreCase(language) ? "since " : "seit ") + MONTH.format(from);
        }
        return MONTH.format(from) + " – " + MONTH.format(to);
    }

    private static String firstOf(String preferred, String fallback) {
        if (preferred != null && !preferred.isBlank()) {
            return preferred.strip();
        }
        return fallback == null || fallback.isBlank() ? null : fallback.strip();
    }
}
