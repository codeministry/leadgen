/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.llm;

import static org.assertj.core.api.Assertions.assertThat;

import de.codeministry.leadgen.config.model.PipelineConfig;
import org.junit.jupiter.api.Test;

/**
 * The writing model is read from {@code llm.models.writing} and from nothing else: a cover
 * letter that falls back to the scoring model is a draft written by a model nobody chose
 * for it, and nothing in the output would say so.
 */
class ChatModelsTest {

    private static final String BASE_URL = "http://localhost:11434/v1";

    private final ChatModels chatModels = new ChatModels();

    @Test
    void writingIsEmptyWhenNoModelIsConfiguredAtAll() {
        assertThat(chatModels.writing(llm(null, null))).isEmpty();
    }

    @Test
    void writingIsEmptyWhenTheWritingModelIsBlank() {
        assertThat(chatModels.writing(llm(null, "  "))).isEmpty();
    }

    @Test
    void writingNeverFallsBackToTheScoringModel() {
        assertThat(chatModels.writing(llm("scorer-model", null))).isEmpty();
        assertThat(chatModels.writing(llm("scorer-model", ""))).isEmpty();
    }

    @Test
    void writingBuildsAModelForExactlyTheConfiguredName() {
        var model = chatModels.writing(llm("scorer-model", "writer-model"));

        assertThat(model).isPresent();
        assertThat(model.get().getOptions().getModel()).isEqualTo("writer-model");
    }

    @Test
    void writingIsEmptyWithoutAnLlmBlockOrModelsBlock() {
        assertThat(chatModels.writing(null)).isEmpty();
        assertThat(chatModels.writing(
                        new PipelineConfig.Llm(ChatModels.OLLAMA, BASE_URL, null, null, false, null, null)))
                .isEmpty();
    }

    private static PipelineConfig.Llm llm(String scoring, String writing) {
        return new PipelineConfig.Llm(
                ChatModels.OLLAMA,
                BASE_URL,
                null,
                null,
                false,
                new PipelineConfig.Llm.Models(null, scoring, writing, null, null),
                null);
    }
}
