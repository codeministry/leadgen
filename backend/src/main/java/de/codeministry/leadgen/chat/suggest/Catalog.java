/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.chat.suggest;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import org.springframework.core.io.ClassPathResource;

/**
 * The server's own sentence for each suggestion and follow-up, in English and German — what the
 * reader sees whenever no model phrases it (ISC-456).
 *
 * <p>Two property files read by hand rather than a {@code ResourceBundle}: a bundle asked for
 * English on a machine whose default locale is German answers German, because its fallback runs
 * through {@code Locale.getDefault()} before the base file. The file name is computed, so
 * {@code LeadGenRuntimeHints} carries a pattern for it.
 */
final class Catalog {

    static final String ENGLISH = "en";
    static final String GERMAN = "de";

    private static final String PATH = "leadgen/i18n/chat-suggest_%s.properties";

    private static final Map<String, Properties> SENTENCES = Map.of(ENGLISH, load(ENGLISH), GERMAN, load(GERMAN));

    private Catalog() {}

    /**
     * The reader's language from {@code Accept-Language}: German when German ranks above English,
     * English otherwise, and English for a missing or malformed header.
     */
    static String language(String acceptLanguage) {
        if (acceptLanguage == null || acceptLanguage.isBlank()) {
            return ENGLISH;
        }
        try {
            for (Locale.LanguageRange range : Locale.LanguageRange.parse(acceptLanguage)) {
                String tag = range.getRange().toLowerCase(Locale.ROOT);
                if (tag.startsWith(GERMAN)) {
                    return GERMAN;
                }
                if (tag.startsWith(ENGLISH)) {
                    return ENGLISH;
                }
            }
        } catch (IllegalArgumentException e) {
            // A header nobody can parse asks for nothing; the API's default answers.
        }
        return ENGLISH;
    }

    /** The sentence under {@code key} in {@code language}, with {@code {name}} filled from {@code params}. */
    static String sentence(String language, String key, Map<String, String> params) {
        String template =
                SENTENCES.getOrDefault(language, SENTENCES.get(ENGLISH)).getProperty(key);
        if (template == null) {
            template = SENTENCES.get(ENGLISH).getProperty(key);
        }
        if (template == null) {
            throw new IllegalStateException("the suggestion catalog has no sentence for " + key);
        }
        String text = template;
        for (Map.Entry<String, String> param : params.entrySet()) {
            text = text.replace("{" + param.getKey() + "}", param.getValue());
        }
        return text;
    }

    /** A candidate's sentence: its own params plus {@code count}. */
    static String sentence(String language, SuggestionCandidate candidate) {
        Map<String, String> params = new java.util.HashMap<>(candidate.params());
        params.put("count", Integer.toString(candidate.count()));
        return sentence(language, candidate.sentenceKey(), params);
    }

    private static Properties load(String language) {
        String path = PATH.formatted(language);
        Properties properties = new Properties();
        try (Reader reader =
                new java.io.InputStreamReader(new ClassPathResource(path).getInputStream(), StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException e) {
            throw new UncheckedIOException("the suggestion catalog is missing: " + path, e);
        }
        return properties;
    }
}
