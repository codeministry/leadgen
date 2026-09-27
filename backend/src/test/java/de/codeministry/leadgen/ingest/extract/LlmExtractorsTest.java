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

import de.codeministry.leadgen.config.model.PipelineConfig.Llm.Models;
import org.junit.jupiter.api.Test;

/**
 * Which model reads a document with no frontmatter.
 *
 * <p>One method, tested on its own because two places read it: the stage that asks, and the
 * Rules screen that says who would answer. They must never disagree.
 */
class LlmExtractorsTest {

    @Test
    void readsTheKeyThatNamesThisStage() {
        // `llm.models.extraction` shipped with `# not read yet` beside it for three
        // releases. This is the assertion that makes the comment removable.
        assertThat(LlmExtractors.modelFor(new Models("a-reader", "a-judge", null, null, null, null, null)))
                .isEqualTo("a-reader");
    }

    @Test
    void fallsBackToTheScoringModelRatherThanAskingForASecondEntry() {
        // Most installations run one model, and there would be nothing to write in the
        // second line — the same argument that made an API key optional for Ollama.
        assertThat(LlmExtractors.modelFor(new Models(null, "a-judge", null, null, null, null, null)))
                .isEqualTo("a-judge");
        assertThat(LlmExtractors.modelFor(new Models("  ", "a-judge", null, null, null, null, null)))
                .isEqualTo("a-judge");
    }

    @Test
    void namesNothingOnAFreshClone() {
        // No model at all is a working state: the tool runs, and a pasted advert stays where
        // it is instead of entering as an offer with no title.
        assertThat(LlmExtractors.modelFor(new Models(null, null, null, null, null, null, null)))
                .isNull();
        assertThat(LlmExtractors.modelFor(null)).isNull();
    }

    @Test
    void takesTheFirstScoringChoiceAndNotAnAlternative() {
        // `scoring_options` exists so two judges can be compared, and the first entry is the
        // configured default. A document is not something there is anything to compare about.
        assertThat(LlmExtractors.modelFor(new Models(null, "a-judge", null, null, "another-judge", null, null)))
                .isEqualTo("a-judge");
    }
}
