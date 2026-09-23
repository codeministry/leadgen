/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * What goes into a retrieval vector. No container: this is string composition, and it is
 * mirrored character for character by {@code docs/samples/measure_embeddings.ts} — the
 * measurement behind the column is worth something only while the two agree.
 */
class AdvertTextTest {

    private static final String BLOCKS = """
            [{"index":0,"kind":"CONTENT","text":"Ablösung eines Monolithen.","reason":"t","by":"RULE"},
             {"index":1,"kind":"AGENCY","text":"Acme Consulting GmbH, HRB 12345.","reason":"t","by":"MODEL"}]
            """;

    @Test
    void takesTheContentBlocksAndLeavesThePortalFurnitureOut() {
        String text = AdvertText.of("Java Entwickler", "Köln", BLOCKS, "the whole page, furniture and all");

        assertThat(text).startsWith("Java Entwickler\nKöln\n");
        assertThat(text).contains("Ablösung eines Monolithen.");
        assertThat(text).doesNotContain("HRB 12345");
        assertThat(text).doesNotContain("the whole page");
    }

    @Test
    void fallsBackToTheWholePageForAnAdvertThatWasNeverSegmented() {
        // `ContentText.of`'s own fallback, and the part worth copying exactly: without it an
        // unsegmented advert contributes nothing and the column quietly describes a smaller
        // corpus than the count beside it claims.
        String text = AdvertText.of("Java Entwickler", null, null, "Migration nach Kubernetes.");

        assertThat(text).isEqualTo("Java Entwickler\nMigration nach Kubernetes.");
    }

    @Test
    void capsAVeryLongAdvertAtTheDocumentedLength() {
        String long_ = "x".repeat(AdvertText.ADVERT_CHARS * 2);

        String text = AdvertText.of("Java Entwickler", "Köln", null, long_);

        assertThat(text).hasSize("Java Entwickler\nKöln\n".length() + AdvertText.ADVERT_CHARS);
    }

    @Test
    void writesNoLineForAFieldThatIsNotThere() {
        // A missing location is a missing line, never the word "null" inside the text that
        // gets embedded — the same rule `OfferEmbedder.text()` follows.
        assertThat(AdvertText.of("Java Entwickler", null, null, null)).isEqualTo("Java Entwickler");
        assertThat(AdvertText.of("Java Entwickler", "  ", null, "  ")).isEqualTo("Java Entwickler");
    }
}
