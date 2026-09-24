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
import de.codeministry.leadgen.content.ContentClassifier;
import de.codeministry.leadgen.fields.FieldExtractor;
import de.codeministry.leadgen.ingest.extract.LlmExtractor;
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
 * @param system the system prompt as it stands right now
 * @param user   the shape of the message an offer arrives in, built by the real builder rather
 *               than written out beside it — a hand-written sample drifts from the method it
 *               describes and nothing fails when it does
 */
public record PromptView(String id, String model, String system, String user) {

    /**
     * All four prompts, in the order the pipeline asks them: a document is read before its
     * advert is segmented, the segmented advert is read for its start, duration and deadline,
     * and all of that happens before anything is scored.
     *
     * <p>The last three carry the same model on purpose, and the screen showing it three times
     * is the point: {@code llm.models.scoring} is read by three stages, which is what keeps the rule
     * that an unread {@code models.*} key is a lie true without adding a second allowlist.
     * The first may carry a different one, because {@code llm.models.extraction} is a key of
     * its own — and which one it would be is exactly what this panel is for.
     *
     * @param extractionModel which model reads a document with no frontmatter. Not the same
     *                        parameter as {@code model} and not interchangeable with it, which is
     *                        why {@code PromptViewTest} pins both.
     */
    public static List<PromptView> all(
            MatchingRules rules, SkillProfile profile, String model, String extractionModel) {
        return List.of(
                new PromptView("extraction", extractionModel, LlmExtractor.instructions(), LlmExtractor.exampleUser()),
                new PromptView("content", model, ContentClassifier.instructions(), ContentClassifier.exampleUser()),
                new PromptView("fields", model, FieldExtractor.instructions(), FieldExtractor.exampleUser()),
                new PromptView(
                        "scoring",
                        model,
                        ChatClientJudge.instructions(rules == null ? null : rules.scoring(), profile),
                        ChatClientJudge.exampleUser()));
    }
}
