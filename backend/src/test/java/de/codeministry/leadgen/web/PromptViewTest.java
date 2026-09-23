/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.web;

import static org.assertj.core.api.Assertions.assertThat;

import de.codeministry.leadgen.config.model.MatchingRules;
import de.codeministry.leadgen.config.model.SkillProfile;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The prompts as the Rules screen shows them.
 *
 * <p>Every assertion here is one of the two defects this panel exists to make visible. Both
 * have shipped: the prompt once described three hard-coded skills of the twenty-nine the
 * profile carries, and the four bounds were once Java constants that matched the weight table
 * by coincidence. A screen showing the *template* would have shown neither.
 */
class PromptViewTest {

    private static final SkillProfile PROFILE = new SkillProfile(
            1,
            "de",
            new SkillProfile.Identity("Somebody", null, "Cologne", null, null, List.of("Backend developer"), "Senior"),
            List.of(new SkillProfile.Skill("Quarkus", 9, null, null)),
            List.of(new SkillProfile.Skill("Kafka", 6, null, null)),
            null,
            null,
            null,
            null,
            null,
            null,
            null);

    private static MatchingRules.Scoring scoring(int roleFit, int vague) {
        return new MatchingRules.Scoring(
                Map.of("role_fit", roleFit),
                Map.of("vague_description", vague),
                4,
                new MatchingRules.Scoring.Thresholds(70, 50, 0));
    }

    @Test
    void carriesTheConfiguredBoundsRatherThanANumberInJava() {
        String system = prompt("scoring", scoring(7, -3)).system();

        assertThat(system).contains("role_fit                  0 to 7");
        assertThat(system).contains("vague_description         -3 to 0");
        // The shipped table says 15 and -10. If either of those appears here, the prompt is
        // reading something other than the weights that were passed in.
        assertThat(system).doesNotContain("0 to 15");
    }

    @Test
    void describesTheProfileTheFileStatesAndNotOneWrittenInJava() {
        String system = prompt("scoring", scoring(15, -10)).system();

        assertThat(system).contains("Backend developer").contains("Senior").contains("Cologne");
        assertThat(system).contains("Quarkus").contains("Kafka");
    }

    @Test
    void rendersWithNoRulesAndNoProfileAtAll() {
        // A fresh clone has neither, and a screen that throws there is a screen that cannot
        // tell somebody why nothing is being scored.
        var prompts = PromptView.all(null, null, null, null);

        assertThat(prompts).hasSize(3);
        assertThat(prompt(prompts, "scoring").system())
                .contains("No profile is configured")
                .contains("0 to 0");
        assertThat(prompt(prompts, "scoring").model()).isNull();
    }

    @Test
    void showsTheShapeOfAnOfferBuiltByTheBuilderThatSendsIt() {
        // Not a hand-written sample: that would be a second description of `describe` and
        // would drift from it in silence, which is the failure this repository designs against.
        String user = prompt("scoring", scoring(15, -10)).user();

        assertThat(user).contains("Title:").contains("Tags:").contains("Description:");
        assertThat(user).contains("Original ad: <the advert with the portal's own furniture removed>");
    }

    @Test
    void offersTheContentClassifierWithItsClosedListOfKinds() {
        PromptView content = prompt("content", scoring(15, -10));

        assertThat(content.system()).contains("CHROME").contains("TAXONOMY").contains("AGENCY");
        // The instruction that makes the whole feature safe: when unsure, leave it in.
        assertThat(content.system()).contains("When you are not sure, omit the block");
        assertThat(content.user()).contains("[0]").contains("[1]");
    }

    @Test
    void namesTheSameModelForTheTwoStagesThatShareAKey() {
        // One key read by two stages, and the screen saying so twice is the point.
        var prompts = PromptView.all(null, null, "a-model", "a-model");

        assertThat(prompts).allMatch(prompt -> "a-model".equals(prompt.model()));
    }

    @Test
    void namesTheExtractionModelWhereItDiffersFromTheScoringOne() {
        // `llm.models.extraction` is a key of its own, so the two can differ — and the
        // panel exists to say which one would answer. Two adjacent String parameters is
        // exactly the shape that gets swapped in silence, so the mapping is pinned here.
        var prompts = PromptView.all(null, null, "the-judge", "the-reader");

        assertThat(prompt(prompts, "extraction").model()).isEqualTo("the-reader");
        assertThat(prompt(prompts, "content").model()).isEqualTo("the-judge");
        assertThat(prompt(prompts, "scoring").model()).isEqualTo("the-judge");
    }

    @Test
    void readsADocumentBeforeAnythingIsSegmentedOrScored() {
        // The order on screen is the order the pipeline asks them in, and the first question
        // is asked of a file nobody has read yet.
        assertThat(PromptView.all(null, null, "a-model", "a-model"))
                .extracting(PromptView::id)
                .containsExactly("extraction", "content", "scoring");
    }

    @Test
    void offersTheDocumentReaderWithTheChecksThatKeepItHonest() {
        PromptView extraction = prompt("extraction", scoring(15, -10));

        assertThat(extraction.system()).contains("character for character").contains("Do not write a description");
        assertThat(extraction.user()).contains("Document:");
    }

    private static PromptView prompt(String id, MatchingRules.Scoring scoring) {
        var rules = new MatchingRules(1, null, scoring, null, null);
        return prompt(PromptView.all(rules, PROFILE, "a-model", "a-model"), id);
    }

    private static PromptView prompt(List<PromptView> prompts, String id) {
        return prompts.stream()
                .filter(prompt -> prompt.id().equals(id))
                .findFirst()
                .orElseThrow();
    }
}
