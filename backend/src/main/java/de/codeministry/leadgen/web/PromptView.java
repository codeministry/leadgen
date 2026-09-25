/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.web;

import de.codeministry.leadgen.config.model.CoverLetterStyle;
import de.codeministry.leadgen.config.model.MatchingRules;
import de.codeministry.leadgen.config.model.PipelineConfig;
import de.codeministry.leadgen.config.model.SkillProfile;
import de.codeministry.leadgen.content.ContentClassifier;
import de.codeministry.leadgen.fields.FieldExtractor;
import de.codeministry.leadgen.ingest.extract.LlmExtractor;
import de.codeministry.leadgen.llm.ModelChoice;
import de.codeministry.leadgen.packaging.CoverLetterWriter;
import de.codeministry.leadgen.score.ChatClientJudge;
import java.util.List;

/**
 * What this configuration actually sends to a language model.
 *
 * <p>The Rules screen already shows the deterministic half of a score — the knockouts, the
 * weight table, the thresholds. The prompt is the other half, and it was the only part of the
 * decision with nowhere to look it up.
 *
 * <p><b>Rendered, never the template.</b> The two things worth checking are exactly the two
 * that get substituted in: whether the configured bounds reached the text, and whether the
 * profile behind "this developer" is the one in `skill-profile.yaml`. Both have been wrong
 * here before — the prompt once described three hard-coded skills of the twenty-nine the
 * profile carries, and the four bounds were once Java constants that matched the weight table
 * by coincidence. A template on screen would have shown neither.
 *
 * <p>Assembled in the web layer rather than in {@code config}, because it needs the stages
 * that own the prompts and the configuration model deliberately depends on no stage.
 *
 * @param id     a closed id, not a sentence: the browser holds the label, the same way it does
 *               for a filter stage or a content kind
 * @param model  which model answers, or null when none is configured. A prompt is a fact about
 *               the configuration, so it is shown either way
 * @param modelKey      the configuration key that decided {@code model}: the stage's own, or
 *                      {@code llm.models.scoring} when the stage's own key is empty and the judge's
 *                      model answers. Null when no model answers. Decided by
 *                      {@link ModelChoice#decidedBy}, the same call the startup log makes.
 * @param ownKey        the stage's own key, {@code llm.models.<stage>}, whether it answered or fell
 *                      back — named here so the browser never rebuilds it from {@code id}. Null
 *                      when no model answers.
 * @param modelFallback true only when the stage has a key of its own, that key is empty and the
 *                      scoring model answers in its place; false for the judge itself, for the
 *                      writer (which has no fallback) and whenever no model answers
 * @param system the system prompt as it stands right now
 * @param user   the shape of the message an offer arrives in, built by the real builder rather
 *               than written out beside it — a hand-written sample drifts from the method it
 *               describes and nothing fails when it does
 */
public record PromptView(
        String id, String model, String modelKey, String ownKey, boolean modelFallback, String system, String user) {

    private static final String SCORING_KEY = "llm.models.scoring";

    /**
     * All five prompts, in the order the pipeline asks them: a document is read before its
     * advert is segmented, the segmented advert is read for its start, duration and deadline,
     * all of that happens before anything is scored, and the letter is written last, for an
     * offer somebody moved to {@code PACKAGED}.
     *
     * <p>Each prompt may carry a different model, because {@code llm.models.extraction},
     * {@code content}, {@code fields} and {@code writing} are keys of their own beside
     * {@code scoring} — and which one would answer is exactly what this panel is for. The first
     * three fall back to scoring when empty; the caller asks {@code ModelChoice} for each rather
     * than reproducing that fallback here.
     *
     * <p>The writer's user message is the one that renders configuration beyond the profile:
     * the style rules and example letters of {@code cover-letter.yaml}, for the profile's primary
     * language, because a letter's language is its advert's and there is no advert on screen.
     *
     * @param extractionModel which model reads a document with no frontmatter. Not the same
     *                        parameter as {@code model} and not interchangeable with it, which is
     *                        why {@code PromptViewTest} pins both.
     * @param contentModel    which model labels an advert's blocks — {@code ModelChoice.content}.
     * @param fieldsModel     which model reads start, duration and apply-by — {@code ModelChoice.fields}.
     * @param writingModel    which model drafts the letter — {@code llm.models.writing}, with no
     *                        fallback to either of the others, so null whenever the key is unset.
     * @param models          the configured {@code llm.models} block, read only to name the key
     *                        that decided each model; null reads as every own key empty.
     */
    public static List<PromptView> all(
            MatchingRules rules,
            SkillProfile profile,
            CoverLetterStyle style,
            String model,
            String extractionModel,
            String contentModel,
            String fieldsModel,
            String writingModel,
            PipelineConfig.Llm.Models models) {
        return List.of(
                fallingBack(
                        "extraction",
                        extractionModel,
                        "llm.models.extraction",
                        models == null ? null : models.extraction(),
                        LlmExtractor.instructions(),
                        LlmExtractor.exampleUser()),
                fallingBack(
                        "content",
                        contentModel,
                        "llm.models.content",
                        models == null ? null : models.content(),
                        ContentClassifier.instructions(),
                        ContentClassifier.exampleUser()),
                fallingBack(
                        "fields",
                        fieldsModel,
                        "llm.models.fields",
                        models == null ? null : models.fields(),
                        FieldExtractor.instructions(),
                        FieldExtractor.exampleUser()),
                own(
                        "scoring",
                        model,
                        SCORING_KEY,
                        ChatClientJudge.instructions(rules == null ? null : rules.scoring(), profile),
                        ChatClientJudge.exampleUser()),
                own(
                        "writing",
                        writingModel,
                        "llm.models.writing",
                        CoverLetterWriter.instructions(),
                        CoverLetterWriter.exampleUser(profile, style)));
    }

    /** A stage whose empty own key hands the question to the scoring model. */
    private static PromptView fallingBack(
            String id, String model, String ownKey, String configured, String system, String user) {
        if (model == null) {
            return new PromptView(id, null, null, null, false, system, user);
        }
        String key = ModelChoice.decidedBy(ownKey, configured);
        return new PromptView(id, model, key, ownKey, !key.equals(ownKey), system, user);
    }

    /** A stage answered by its own key or by nothing: the judge, and the writer with no fallback. */
    private static PromptView own(String id, String model, String key, String system, String user) {
        String decided = model == null ? null : key;
        return new PromptView(id, model, decided, decided, false, system, user);
    }
}
