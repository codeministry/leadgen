/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.llm;

import de.codeministry.leadgen.config.model.PipelineConfig;
import java.util.List;
import java.util.Optional;

/**
 * Which configured model a stage asks: the one place that reads {@code scoringChoices()}.
 *
 * <p>The default is the first scoring choice, so "nothing was asked for" and "the configured
 * one was asked for" are the same answer and cannot drift apart between the judge, the
 * classifier, the field extractor and the LLM extractor.
 */
public final class ModelChoice {

    private ModelChoice() {}

    /**
     * Every model that may be asked to judge, the configured default first. Empty without a
     * model, which is a working state rather than an error.
     */
    public static List<String> scoring(PipelineConfig.Llm llm) {
        return llm == null ? List.of() : scoring(llm.models());
    }

    /**
     * The first scoring choice, or none when nothing is configured.
     */
    public static Optional<String> defaultScoring(PipelineConfig.Llm llm) {
        return scoring(llm).stream().findFirst();
    }

    /**
     * {@code extraction} when it names one, the first scoring choice otherwise, and none when
     * neither does — which is the fresh clone, where the tool runs without a model at all.
     */
    public static Optional<String> extraction(PipelineConfig.Llm.Models models) {
        if (models == null) {
            return Optional.empty();
        }
        String configured = models.extraction();
        if (configured != null && !configured.isBlank()) {
            return Optional.of(configured);
        }
        return scoring(models).stream().findFirst();
    }

    private static List<String> scoring(PipelineConfig.Llm.Models models) {
        return models == null ? List.of() : models.scoringChoices();
    }
}
