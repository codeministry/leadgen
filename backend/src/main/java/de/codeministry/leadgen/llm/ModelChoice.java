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

    private static final String SCORING_KEY = "llm.models.scoring";

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
        return ownOrScoring(models, models == null ? null : models.extraction());
    }

    /**
     * {@code content} when it names one, the first scoring choice otherwise, and none when
     * neither does. The content classifier asks a bounded question, which a smaller model than
     * the judge can answer; empty keeps the one-line configuration working.
     */
    public static Optional<String> content(PipelineConfig.Llm.Models models) {
        return ownOrScoring(models, models == null ? null : models.content());
    }

    /**
     * {@code fields} when it names one, the first scoring choice otherwise, and none when
     * neither does. Same argument as {@link #content}.
     */
    public static Optional<String> fields(PipelineConfig.Llm.Models models) {
        return ownOrScoring(models, models == null ? null : models.fields());
    }

    /**
     * The key the startup log names beside the model: the stage's own when it holds a value,
     * {@code llm.models.scoring} when the fallback answered. Decided from the configured value
     * and not by comparing model names, which would name the wrong key the day both hold the
     * same model.
     */
    public static String decidedBy(String ownKey, String configured) {
        return configured != null && !configured.isBlank() ? ownKey : SCORING_KEY;
    }

    private static Optional<String> ownOrScoring(PipelineConfig.Llm.Models models, String configured) {
        if (models == null) {
            return Optional.empty();
        }
        if (configured != null && !configured.isBlank()) {
            return Optional.of(configured.trim());
        }
        return scoring(models).stream().findFirst();
    }

    private static List<String> scoring(PipelineConfig.Llm.Models models) {
        return models == null ? List.of() : models.scoringChoices();
    }
}
