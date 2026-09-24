/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.packaging;

import de.codeministry.leadgen.content.ContentText;
import de.codeministry.leadgen.filter.TextFold;
import java.util.regex.Pattern;

/**
 * The advert as the package reads it, folded once and matched on word boundaries.
 *
 * <p>It lives in its own class because two readers must see the same text: the build, which
 * picks skills and reference projects from it, and {@link CoverLetterGuard}, which refuses a
 * draft that claims what the advert never asked for. Two copies of the folding would drift, and
 * a guard reading a different advert than the build is a guard that rejects its own letters.
 */
final class AdText {

    private AdText() {}

    /**
     * The content blocks when the advert has been read that way, {@code full_text} when it has
     * not. A portal's own tag cloud otherwise decides which reference projects a cover letter
     * pitches.
     */
    static String of(String title, String description, String contentBlocks, String fullText) {
        String advert = ContentText.of(contentBlocks, fullText == null ? "" : fullText);
        return TextFold.fold("%s %s %s".formatted(title, description == null ? "" : description, advert));
    }

    /** Whether the folded text names the keyword as a whole word or phrase. */
    static boolean names(String folded, String keyword) {
        Pattern pattern = TextFold.keyword(keyword);
        return pattern != null && pattern.matcher(folded).find();
    }
}
