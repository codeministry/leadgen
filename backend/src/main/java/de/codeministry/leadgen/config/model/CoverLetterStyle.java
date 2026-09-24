/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.config.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * How a cover letter should read, per language of the advert: the phrases it must not
 * contain, how long it may be, and a few notes on its structure — plus the operator's own
 * example letters, which a prompt may use for tone and never for facts.
 *
 * <p><b>The shipped default carries rules and no example.</b> An example letter is somebody's
 * letter, with somebody's projects in it, so it is a personal datum and lives only in the
 * config directory. The layers override file by file: an override that names only {@code de}
 * leaves {@code en} without rules rather than inheriting the default's.
 *
 * <p>Language keys are ISO 639-1 codes in lower case, the same keys the CV variants use.
 *
 * @param rules    the style rules keyed by language
 * @param examples example letters keyed by language, used for tone only
 */
public record CoverLetterStyle(
        @NotNull Integer version, Map<String, @Valid Rules> rules, Map<String, List<@NotBlank String>> examples) {

    public CoverLetterStyle {
        rules = rules == null ? Map.of() : Map.copyOf(rules);
        examples = examples == null ? Map.of() : Map.copyOf(examples);
    }

    /**
     * The rules for one language, never null. A language the file does not name gets
     * {@link Rules#UNCONSTRAINED}: nothing banned and no limit, because inventing a limit for
     * a language nobody wrote rules for would reject letters on a number nobody chose.
     */
    public Rules forLanguage(String language) {
        return rules.getOrDefault(key(language), Rules.UNCONSTRAINED);
    }

    /**
     * Whether the file names rules for this language, as opposed to {@link #forLanguage}
     * falling back to {@link Rules#UNCONSTRAINED}. The loader warns on a letter language
     * without them, because an unguarded letter looks exactly like a clean pass.
     */
    public boolean hasRulesFor(String language) {
        return rules.containsKey(key(language));
    }

    /**
     * The example letters for one language, never null and empty when none are configured.
     */
    public List<String> examplesFor(String language) {
        return examples.getOrDefault(key(language), List.of());
    }

    private static String key(String language) {
        return language == null ? "" : language.strip().toLowerCase(Locale.ROOT);
    }

    /**
     * The style rules for one language, and the three lines of the letter that are fixed text
     * rather than the model's: how it greets when the advert names nobody, how it greets a named
     * person, and how it closes. They are content in the letter's language, so they live here
     * and never as literals in the code that assembles the letter.
     *
     * @param bannedPhrases     phrases a letter must not contain; compared case-folded by the guard
     * @param wordLimit         the most words the letter body may have
     * @param structureNotes    short instructions on structure, handed to the model as they are
     * @param neutralSalutation the greeting when the advert names no contact person
     * @param namedSalutation   the greeting for a named person; {@code {name}} is replaced by the
     *                          honorific and surname as the advert writes them
     * @param closing           the line above the signature
     */
    public record Rules(
            List<@NotBlank String> bannedPhrases,
            @NotNull @Min(1) Integer wordLimit,
            List<@NotBlank String> structureNotes,
            @NotBlank String neutralSalutation,

            @NotBlank @Pattern(regexp = "(?s).*\\{name\\}.*", message = "must contain the name placeholder")
            String namedSalutation,

            @NotBlank String closing) {

        /**
         * No banned phrase, no effective limit, no notes — and no letter lines, because a
         * language nobody wrote rules for has no greeting anybody chose either. A drafted letter
         * is therefore never assembled from these; the template writes it instead.
         */
        public static final Rules UNCONSTRAINED = new Rules(List.of(), Integer.MAX_VALUE, List.of(), null, null, null);

        public Rules {
            bannedPhrases = bannedPhrases == null ? List.of() : List.copyOf(bannedPhrases);
            structureNotes = structureNotes == null ? List.of() : List.copyOf(structureNotes);
        }
    }
}
