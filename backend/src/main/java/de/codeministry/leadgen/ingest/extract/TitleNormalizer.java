/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.ingest.extract;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Turns a title into the form comparisons run on. Every title comparison in this
 * repository goes through here, so two of them can never disagree.
 *
 * <p>The gender suffixes have to go first: `(m/w/d)`, `(w/m/d)` and `(m/f/d)` are
 * decoration that the same ad carries in different spellings across portals, and one
 * project appears up to eight times that way.
 *
 * <p><b>`<mark>` is stripped here, because the expectation that the HTML parser had already
 * done it did not survive contact with the live corpus.</b> Some sources wrap the subscriber's
 * own search terms in it and the tag reaches the title as text. It then does not simply
 * disappear: `[^a-z0-9]+` turns the angle brackets into spaces and leaves the word `mark`
 * standing, twice, so `<mark>DevOps</mark> Engineer` fingerprints as `mark devops mark
 * engineer` and never meets `devops engineer`. Measured on 13240 offers: 402 titles carried
 * the tag, 384 of them were unmerged, and at least 170 match an existing fingerprint once it
 * is gone. The first pair the embedding strategies ever flagged was one of them, at a cosine
 * similarity of 0.9528 — a model call to half-find what a regex finds for free.
 */
public final class TitleNormalizer {

    /**
     * The tag and nothing else. A general `<[^>]+>` would be no safer: everything it could
     * additionally match is punctuation that the non-alphanumeric pass removes anyway, and a
     * narrow pattern says which markup was actually measured in the wild.
     */
    private static final Pattern SEARCH_TERM_MARKUP = Pattern.compile("</?mark>", Pattern.CASE_INSENSITIVE);

    private static final Pattern GENDER_SUFFIX =
            Pattern.compile("\\((?:m/w/d|w/m/d|m/f/d)\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^a-z0-9]+");

    private TitleNormalizer() {}

    public static String normalize(String title) {
        if (title == null) {
            return "";
        }
        String decomposed = Normalizer.normalize(title, Normalizer.Form.NFKD).toLowerCase(Locale.ROOT);
        // Before the gender suffixes, because the tag can sit inside one: a search term
        // matching "w" would arrive as `(m/<mark>w</mark>/d)` and the suffix pattern would
        // then not recognise its own shape.
        String withoutMarkup = SEARCH_TERM_MARKUP.matcher(decomposed).replaceAll("");
        String withoutGender = GENDER_SUFFIX.matcher(withoutMarkup).replaceAll(" ");
        return NON_ALPHANUMERIC.matcher(withoutGender).replaceAll(" ").trim().replaceAll("\\s+", " ");
    }
}
