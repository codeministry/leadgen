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
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Builds the field extractor the current configuration asks for, or none.
 *
 * <p>Per run, not once at startup, for the same reason {@code Judges} and {@code Classifiers}
 * are: the configuration is hot-reloadable, so a key added to `.env` should start filling in
 * start dates without a restart.
 *
 * <p><b>It reads {@code llm.models.scoring}</b>, the third stage to do so. A {@code
 * models.fields} key would mean a third allowlist, a third entry in the run history and a
 * third select in the header, for a bounded question answered in three lines of JSON. The
 * repository's rule is that a {@code models.*} key nothing reads is a lie; one key that three
 * stages read keeps that true, and the shipped file says so.
 *
 * <p>The configured default and never a model the browser named, for the same reason the
 * classifier uses one: which judge scores is a parameter of the run because two judges are
 * two scales and comparing them is the point, while what an advert says about its start date
 * is a fact about the advert — there is nothing to compare, and nothing worth letting a
 * request decide.
 */
@Slf4j
@Component
public class FieldExtractors {

    /**
     * Its own mapper, not the web one, for the same reason the judge and the classifier have
     * theirs: this reads a model's answer, which is plain JSON with none of the conventions
     * the HTTP layer is configured for.
     */
    private final ObjectMapper json = new ObjectMapper();

    private final ConfigRegistry config;
    private final ChatModels chatModels;

    FieldExtractors(ConfigRegistry config, ChatModels chatModels) {
        this.config = config;
        this.chatModels = chatModels;
    }

    /**
     * The extractor this run may ask, or nothing when the configuration cannot reach a model.
     *
     * <p>Nothing is a working state: the columns keep whatever the enrichment patterns wrote,
     * which is less than this stage would find and is not nothing.
     */
    public Optional<FieldExtractor> current() {
        PipelineConfig.Llm llm = config.snapshot().application().llm();
        if (llm == null) {
            return Optional.empty();
        }
        List<String> choices = llm.models() == null ? List.of() : llm.models().scoringChoices();
        if (choices.isEmpty()) {
            return Optional.empty();
        }
        String model = choices.getFirst();
        return chatModels.of(llm, model).map(chatModel -> new FieldExtractor(chatModel, model, json));
    }
}
