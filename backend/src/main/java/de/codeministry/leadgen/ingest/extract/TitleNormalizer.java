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
 * <p>Markup is stripped before this by the HTML parser, which is why there is no
 * `<mark>` handling here. Search terms arrive wrapped in it on some sources; the
 * current sample corpus has none, so that is a documented expectation and not a
 * measured one.
 */
public final class TitleNormalizer {

    private static final Pattern GENDER_SUFFIX =
            Pattern.compile("\\((?:m/w/d|w/m/d|m/f/d)\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^a-z0-9]+");

    private TitleNormalizer() {}

    public static String normalize(String title) {
        if (title == null) {
            return "";
        }
        String decomposed = Normalizer.normalize(title, Normalizer.Form.NFKD).toLowerCase(Locale.ROOT);
        String withoutGender = GENDER_SUFFIX.matcher(decomposed).replaceAll(" ");
        return NON_ALPHANUMERIC.matcher(withoutGender).replaceAll(" ").trim().replaceAll("\\s+", " ");
    }
}
