/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.web;

import de.codeministry.leadgen.config.model.MatchingRules;
import de.codeministry.leadgen.config.model.SkillProfile;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

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
            new SkillProfile.Identity(
                    "Somebody", null, "Cologne", null, null, List.of("Backend developer"), "Senior"),
            List.of(new SkillProfile.Skill("Quarkus", 9, null, null)),
            List.of(new SkillProfile.Skill("Kafka", 6, null, null)),
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
        var prompts = PromptView.all(null, null, null);

        assertThat(prompts).hasSize(2);
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
    void namesTheSameModelForBoth() {
        // One key read by two stages, and the screen saying so twice is the point.
        var prompts = PromptView.all(null, null, "a-model");

        assertThat(prompts).allMatch(prompt -> "a-model".equals(prompt.model()));
    }

    private static PromptView prompt(String id, MatchingRules.Scoring scoring) {
        var rules = new MatchingRules(1, null, scoring, null, null, null);
        return prompt(PromptView.all(rules, PROFILE, "a-model"), id);
    }

    private static PromptView prompt(List<PromptView> prompts, String id) {
        return prompts.stream()
                .filter(prompt -> prompt.id().equals(id))
                .findFirst()
                .orElseThrow();
    }
}
