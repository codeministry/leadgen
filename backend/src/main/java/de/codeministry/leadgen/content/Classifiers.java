/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.content;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.codeministry.leadgen.config.ConfigRegistry;
import de.codeministry.leadgen.config.model.PipelineConfig;
import de.codeministry.leadgen.llm.ChatModels;
import de.codeministry.leadgen.llm.ModelChoice;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Builds the content classifier the current configuration asks for, or none.
 *
 * <p>Per run, not once at startup, for the same reason {@code Judges} is: the configuration
 * is hot-reloadable, so a key added to `.env` should start labelling without a restart.
 *
 * <p><b>It reads {@code llm.models.content}, and {@code llm.models.scoring} when that is
 * empty.</b> A bounded classifier that answers in three lines of JSON is a question a smaller
 * model than the judge can answer. The key is a setting and not a per-run choice — no second
 * allowlist, no history entry, no select — because {@code content_model} already records which
 * model answered. {@link ModelChoice#content} decides, and the log names the key that won.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class Classifiers {

    /**
     * The line last written to the log. Once per change rather than once per run: the
     * configuration is hot-reloadable, so a new model is worth a line and the same one is not.
     */
    private final AtomicReference<String> announced = new AtomicReference<>();

    /**
     * Its own mapper, not the web one, for the same reason the judge has one: this reads a
     * model's answer, which is plain JSON with none of the conventions the HTTP layer is
     * configured for.
     */
    private final ObjectMapper json = new ObjectMapper();

    private final ConfigRegistry config;
    private final ChatModels chatModels;

    /**
     * The classifier this run may ask, or nothing when the configuration cannot reach a model.
     *
     * <p>Nothing is a working state, not an error: without one, the rules and the cache still
     * label what they recognise, and everything else stays visible.
     */
    public Optional<ContentClassifier> current() {
        PipelineConfig.Llm llm = config.snapshot().application().llm();
        // Its own configured key (ModelChoice) and never one the browser named. Which model
        // judges is a parameter of the run because two judges are two scales and the
        // comparison is the point; a label is a fact about a paragraph, so there is nothing to
        // compare and nothing worth letting a request decide.
        PipelineConfig.Llm.Models models = llm == null ? null : llm.models();
        return ModelChoice.content(models)
                .flatMap(model -> chatModels.of(llm, model).map(chatModel -> {
                    // After a client exists: the line means "will be asked", not "was chosen".
                    announce(model, ModelChoice.decidedBy("llm.models.content", models.content()));
                    return new ContentClassifier(chatModel, model, json);
                }));
    }

    private void announce(String model, String key) {
        String line = "Content blocks are labelled by '" + model + "', from " + key;
        if (!line.equals(announced.getAndSet(line))) {
            log.info("{}", line);
        }
    }
}
