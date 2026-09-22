/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.ingest.extract;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The form every title comparison in this repository runs on.
 *
 * <p>Tested on its own because the fingerprint is written once, at ingest, and never looked at
 * again: a title normalised wrongly is a duplicate that is simply never found, with no error
 * anywhere and no second chance.
 */
class TitleNormalizerTest {

    @Test
    void stripsTheSearchTermMarkupInsteadOfLeavingTheWordMarkInTheFingerprint() {
        // `[^a-z0-9]+` removes the angle brackets and leaves the tag's own name standing, so
        // this used to fingerprint as "mark devops mark engineer" and never meet its twin.
        // Measured on 13240 live offers: 402 titles carried the tag and 384 were unmerged.
        assertThat(TitleNormalizer.normalize("<mark>DevOps</mark> Engineer"))
                .isEqualTo(TitleNormalizer.normalize("DevOps Engineer"))
                .isEqualTo("devops engineer");
    }

    @Test
    void stripsItWhateverCaseTheSourceWroteItIn() {
        assertThat(TitleNormalizer.normalize("<MARK>Java</MARK> Entwickler")).isEqualTo("java entwickler");
    }

    @Test
    void stripsItBeforeTheGenderSuffixSoTheSuffixStillHasItsShape() {
        // A search term matching "w" arrives as `(m/<mark>w</mark>/d)`, and the suffix pattern
        // does not recognise its own shape until the tag is gone.
        assertThat(TitleNormalizer.normalize("Java Entwickler (m/<mark>w</mark>/d)"))
                .isEqualTo(TitleNormalizer.normalize("Java Entwickler (m/w/d)"));
    }

    @Test
    void leavesTheWordMarkAloneWhenItIsPartOfTheTitle() {
        // Only the tag goes. A real word that happens to be "mark" is what the advert said.
        assertThat(TitleNormalizer.normalize("Mark Logic Entwickler")).isEqualTo("mark logic entwickler");
    }

    @Test
    void stillFoldsTheGenderSuffixesAndTheDiacritics() {
        // The behaviour the markup strip had to leave untouched.
        assertThat(TitleNormalizer.normalize("Senior Java Entwickler (w/m/d)"))
                .isEqualTo(TitleNormalizer.normalize("Senior Java Entwickler (m/f/d)"));
        assertThat(TitleNormalizer.normalize("Entwickler München")).isEqualTo("entwickler mu nchen");
        assertThat(TitleNormalizer.normalize(null)).isEmpty();
    }
}
