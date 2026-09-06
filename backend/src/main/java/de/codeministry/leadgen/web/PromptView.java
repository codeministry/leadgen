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
 * <p>Assembled in the web layer rather than in {@code config}, because it needs the two stages
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
     * Both prompts, in the order the pipeline asks them.
     *
     * <p>They carry the same model on purpose, and the screen showing it twice is the point:
     * {@code llm.models.scoring} is read by two stages, which is what keeps the rule that an
     * unread {@code models.*} key is a lie true without adding a second allowlist.
     */
    public static List<PromptView> all(MatchingRules rules, SkillProfile profile, String model) {
        return List.of(
                new PromptView(
                        "content",
                        model,
                        ContentClassifier.instructions(),
                        ContentClassifier.exampleUser()),
                new PromptView(
                        "scoring",
                        model,
                        ChatClientJudge.instructions(rules == null ? null : rules.scoring(), profile),
                        ChatClientJudge.exampleUser()));
    }
}
