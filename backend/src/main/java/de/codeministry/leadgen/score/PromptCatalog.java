/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.score;

import de.codeministry.leadgen.config.ConfigRegistry;
import de.codeministry.leadgen.ingest.extract.LlmExtractors;
import de.codeministry.leadgen.llm.ChatModels;
import de.codeministry.leadgen.llm.ModelChoice;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Every prompt this configuration sends, and the scoring models a run may choose, as they stand
 * right now.
 *
 * <p>One place for both readers: the workflow screen asks through {@code GET /prompts} and
 * {@code GET /scoring-models}, an MCP client through {@code leadgen_get_pipeline_config}. Assembled
 * in the controller they would have been assembled twice, and the second copy is the one that
 * drifts when a new stage gets a model key of its own.
 */
@Service
@RequiredArgsConstructor
public class PromptCatalog {

    private final ConfigRegistry config;
    private final Judges judges;

    public List<PromptView> prompts() {
        var snapshot = config.snapshot();
        var choices = judges.choices();
        var llm = snapshot.application().llm();
        var models = llm == null ? null : llm.models();
        return PromptView.all(
                snapshot.rules(),
                snapshot.profile(),
                snapshot.coverLetter(),
                choices.isEmpty() ? null : choices.getFirst(),
                // Each stage's own choice, asked rather than reproduced: a copy of it here
                // would name one model on the screen while the run used the other.
                LlmExtractors.modelFor(models),
                ModelChoice.content(models).orElse(null),
                ModelChoice.fields(models).orElse(null),
                ChatModels.writingModelFor(models),
                models);
    }

    public ScoringModels scoringModels() {
        return ScoringModels.of(judges.choices());
    }
}
