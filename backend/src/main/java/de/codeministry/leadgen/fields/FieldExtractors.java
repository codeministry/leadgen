/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.fields;

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
 * Builds the field extractor the current configuration asks for, or none.
 *
 * <p>Per run, not once at startup, for the same reason {@code Judges} and {@code Classifiers}
 * are: the configuration is hot-reloadable, so a key added to `.env` should start filling in
 * start dates without a restart.
 *
 * <p><b>It reads {@code llm.models.fields}, and {@code llm.models.scoring} when that is
 * empty.</b> A bounded question answered in three lines of JSON is one a smaller model than the
 * judge can answer. The key is a setting and not a per-run choice — no second allowlist, no
 * history entry, no select — because {@code fields_model} already records which model
 * answered. {@link ModelChoice#fields} decides, and the log names the key that won.
 *
 * <p>A configured key and never a model the browser named, for the same reason the
 * classifier uses one: which judge scores is a parameter of the run because two judges are
 * two scales and comparing them is the point, while what an advert says about its start date
 * is a fact about the advert — there is nothing to compare, and nothing worth letting a
 * request decide.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FieldExtractors {

    /**
     * The line last written to the log. Once per change rather than once per run: the
     * configuration is hot-reloadable, so a new model is worth a line and the same one is not.
     */
    private final AtomicReference<String> announced = new AtomicReference<>();

    /**
     * Its own mapper, not the web one, for the same reason the judge and the classifier have
     * theirs: this reads a model's answer, which is plain JSON with none of the conventions
     * the HTTP layer is configured for.
     */
    private final ObjectMapper json = new ObjectMapper();

    private final ConfigRegistry config;
    private final ChatModels chatModels;

    /**
     * The extractor this run may ask, or nothing when the configuration cannot reach a model.
     *
     * <p>Nothing is a working state: the columns keep whatever the enrichment patterns wrote,
     * which is less than this stage would find and is not nothing.
     */
    public Optional<FieldExtractor> current() {
        PipelineConfig.Llm llm = config.snapshot().application().llm();
        PipelineConfig.Llm.Models models = llm == null ? null : llm.models();
        return ModelChoice.fields(models)
                .flatMap(model -> chatModels.of(llm, model).map(chatModel -> {
                    // After a client exists: the line means "will be asked", not "was chosen".
                    announce(model, ModelChoice.decidedBy("llm.models.fields", models.fields()));
                    return new FieldExtractor(chatModel, model, json);
                }));
    }

    private void announce(String model, String key) {
        String line = "Start, duration and apply-by are read by '" + model + "', from " + key;
        if (!line.equals(announced.getAndSet(line))) {
            log.info("{}", line);
        }
    }
}
