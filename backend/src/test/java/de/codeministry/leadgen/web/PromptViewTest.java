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

import de.codeministry.leadgen.config.model.CoverLetterStyle;
import de.codeministry.leadgen.config.model.MatchingRules;
import de.codeministry.leadgen.config.model.PipelineConfig;
import de.codeministry.leadgen.config.model.SkillProfile;
import de.codeministry.leadgen.fields.FieldExtractor;
import de.codeministry.leadgen.packaging.CoverLetterWriter;
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

    /**
     * Rules for two languages that differ in every value, so the test can tell which one the
     * screen rendered: the profile's primary locale is German, and the English rules are the
     * ones that must not show up.
     */
    private static final CoverLetterStyle STYLE = new CoverLetterStyle(
            1,
            Map.of(
                    "de",
                            new CoverLetterStyle.Rules(
                                    List.of("hervorragend", "Synergien"),
                                    180,
                                    List.of("Open with the requirement you match best."),
                                    "Sehr geehrte Damen und Herren,",
                                    "Sehr geehrte {name},",
                                    "Mit freundlichen Grüßen"),
                    "en",
                            new CoverLetterStyle.Rules(
                                    List.of("passionate"),
                                    240,
                                    List.of("Keep it to three paragraphs."),
                                    "Dear Sir or Madam,",
                                    "Dear {name},",
                                    "Kind regards")),
            Map.of("de", List.of("Ein Beispielbrief, nur für den Ton.")));

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
        var prompts = PromptView.all(null, null, null, null, null, null, null, null, null);

        assertThat(prompts).hasSize(5);
        assertThat(prompt(prompts, "scoring").system())
                .contains("No profile is configured")
                .contains("0 to 0");
        assertThat(prompt(prompts, "scoring").model()).isNull();
        // The writer too: no style file means no rules and no examples, not a blank screen.
        assertThat(prompt(prompts, "writing").user()).contains("STYLE RULES").doesNotContain("Banned phrases");
        assertThat(prompt(prompts, "writing").model()).isNull();
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
        var prompts = PromptView.all(null, null, null, "a-model", "a-model", "a-model", "a-model", "a-model", null);

        assertThat(prompts).allMatch(prompt -> "a-model".equals(prompt.model()));
    }

    @Test
    void namesTheExtractionModelWhereItDiffersFromTheScoringOne() {
        // `llm.models.extraction` is a key of its own, so the two can differ — and the
        // panel exists to say which one would answer. Two adjacent String parameters is
        // exactly the shape that gets swapped in silence, so the mapping is pinned here.
        var prompts = PromptView.all(
                null, null, null, "the-judge", "the-reader", "the-labeller", "the-date-reader", "the-writer", null);

        assertThat(prompt(prompts, "extraction").model()).isEqualTo("the-reader");
        assertThat(prompt(prompts, "content").model()).isEqualTo("the-labeller");
        assertThat(prompt(prompts, "fields").model()).isEqualTo("the-date-reader");
        assertThat(prompt(prompts, "scoring").model()).isEqualTo("the-judge");
        // `llm.models.writing` is a third key, and the one with no fallback to any other.
        assertThat(prompt(prompts, "writing").model()).isEqualTo("the-writer");
    }

    @Test
    void readsADocumentBeforeAnythingIsSegmentedOrScored() {
        // The order on screen is the order the pipeline asks them in, and the first question
        // is asked of a file nobody has read yet.
        assertThat(PromptView.all(null, null, null, "a-model", "a-model", "a-model", "a-model", "a-model", null))
                .extracting(PromptView::id)
                .containsExactly("extraction", "content", "fields", "scoring", "writing");
    }

    @Test
    void offersTheCoverLetterWriterWithTheInstructionsItReallySends() {
        // ISC-326: the letter is the one model call the screen could not show. The system
        // prompt is the writer's own constant, not a paraphrase kept beside it.
        PromptView writing = prompt("writing", scoring(15, -10));

        assertThat(writing.system()).isEqualTo(CoverLetterWriter.instructions());
        assertThat(writing.system()).contains("Never claim a skill, a project, a client, a year or a number");
    }

    @Test
    void rendersTheStyleRulesTheFileStatesIntoTheWritersUserMessage() {
        // The rules are configuration and reach the model through the user message, so that is
        // where a person checks whether `cover-letter.yaml` arrived: the limit, the notes, the
        // banned phrases and the example letter, for the profile's primary language.
        String user = prompt("writing", scoring(15, -10)).user();

        assertThat(user).contains("Letter language: de");
        assertThat(user).contains("The body has at most 180 words");
        assertThat(user).contains("Open with the requirement you match best.");
        assertThat(user).contains("Banned phrases: \"hervorragend\", \"Synergien\"");
        assertThat(user).contains("Neutral salutation: Sehr geehrte Damen und Herren,");
        assertThat(user).contains("Ein Beispielbrief, nur für den Ton.");
        // The English block exists in the same file and must not leak into the German render.
        assertThat(user).doesNotContain("240").doesNotContain("passionate").doesNotContain("Dear Sir");
    }

    @Test
    void offersTheProfilesSkillsAndAPlaceholderAdvertToTheWriter() {
        // Skills are configuration and are rendered; the advert, the projects and the start
        // date belong to one offer and stand as placeholders, built by `describe` itself.
        String user = prompt("writing", scoring(15, -10)).user();

        assertThat(user).contains("PROFILE SKILLS").contains("- Quarkus").contains("- Kafka");
        assertThat(user).contains("ADVERT\n<");
        assertThat(user).contains("PROJECTS\n- <");
        assertThat(user).contains("Contact person named in the advert: none — use the neutral salutation");
    }

    @Test
    void offersTheFieldExtractorWithTheMessageItReallySends() {
        // FIELDS calls a model too, so the screen that shows every prompt shows this one.
        PromptView fields = prompt("fields", scoring(15, -10));

        assertThat(fields.system()).isEqualTo(FieldExtractor.instructions());
        assertThat(fields.user()).isEqualTo(FieldExtractor.exampleUser());
    }

    @Test
    void offersTheDocumentReaderWithTheChecksThatKeepItHonest() {
        PromptView extraction = prompt("extraction", scoring(15, -10));

        assertThat(extraction.system()).contains("character for character").contains("Do not write a description");
        assertThat(extraction.user()).contains("Document:");
    }

    // --- ISC-386: the screen names the key that decided each model, as text, and says when the
    // scoring model answers because the stage's own key is empty.

    @Test
    void namesTheKeyThatDecidedEachModelAndWhenTheFallbackAnswered() {
        // `content` holds a value of its own; `fields` and `extraction` are empty and fall back.
        var models = new PipelineConfig.Llm.Models("", "the-judge", "the-writer", null, null, "the-labeller", "");
        var prompts = PromptView.all(
                null, null, null, "the-judge", "the-judge", "the-labeller", "the-judge", "the-writer", models);

        assertThat(prompt(prompts, "content").modelKey()).isEqualTo("llm.models.content");
        assertThat(prompt(prompts, "content").modelFallback()).isFalse();
        assertThat(prompt(prompts, "fields").modelKey()).isEqualTo("llm.models.scoring");
        assertThat(prompt(prompts, "fields").modelFallback()).isTrue();
        assertThat(prompt(prompts, "extraction").modelKey()).isEqualTo("llm.models.scoring");
        assertThat(prompt(prompts, "extraction").modelFallback()).isTrue();
        // The judge's key is its own, so it never "falls back" to itself.
        assertThat(prompt(prompts, "scoring").modelKey()).isEqualTo("llm.models.scoring");
        assertThat(prompt(prompts, "scoring").modelFallback()).isFalse();
        // The writer has no fallback at all.
        assertThat(prompt(prompts, "writing").modelKey()).isEqualTo("llm.models.writing");
        assertThat(prompt(prompts, "writing").modelFallback()).isFalse();
        // The stage's own key is named by the server, whether it answered or fell back.
        assertThat(prompts)
                .extracting(PromptView::ownKey)
                .containsExactly(
                        "llm.models.extraction",
                        "llm.models.content",
                        "llm.models.fields",
                        "llm.models.scoring",
                        "llm.models.writing");
    }

    @Test
    void namesNoKeyWhereNoModelAnswers() {
        // A stage with no model has no key that decided one, and nothing fell back.
        var models = new PipelineConfig.Llm.Models(null, null, null, null, null, null, null);
        var prompts = PromptView.all(null, null, null, null, null, null, null, null, models);

        assertThat(prompts).allSatisfy(prompt -> {
            assertThat(prompt.modelKey()).isNull();
            assertThat(prompt.ownKey()).isNull();
            assertThat(prompt.modelFallback()).isFalse();
        });
    }

    private static PromptView prompt(String id, MatchingRules.Scoring scoring) {
        var rules = new MatchingRules(1, null, scoring, null, null);
        return prompt(
                PromptView.all(rules, PROFILE, STYLE, "a-model", "a-model", "a-model", "a-model", "a-model", null), id);
    }

    private static PromptView prompt(List<PromptView> prompts, String id) {
        return prompts.stream()
                .filter(prompt -> prompt.id().equals(id))
                .findFirst()
                .orElseThrow();
    }
}
