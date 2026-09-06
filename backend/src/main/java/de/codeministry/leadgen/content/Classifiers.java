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
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Builds the content classifier the current configuration asks for, or none.
 *
 * <p>Per run, not once at startup, for the same reason {@code Judges} is: the configuration
 * is hot-reloadable, so a key added to `.env` should start labelling without a restart.
 *
 * <p><b>It reads {@code llm.models.scoring}, deliberately.</b> Adding a {@code models.content}
 * key would mean a second allowlist, a second entry in the run history and a second select in
 * the header — for a bounded classifier that answers with three lines of JSON and is asked the
 * same kind of question the judge is. The repository's rule is that a {@code models.*} key
 * nothing reads is a lie; the honest way to keep that true is to have one key that two stages
 * read, and to say so in the shipped file.
 */
@Slf4j
@Component
public class Classifiers {

    /**
     * Its own mapper, not the web one, for the same reason the judge has one: this reads a
     * model's answer, which is plain JSON with none of the conventions the HTTP layer is
     * configured for.
     */
    private final ObjectMapper json = new ObjectMapper();

    private final ConfigRegistry config;
    private final ChatModels chatModels;

    Classifiers(ConfigRegistry config, ChatModels chatModels) {
        this.config = config;
        this.chatModels = chatModels;
    }

    /**
     * The classifier this run may ask, or nothing when the configuration cannot reach a model.
     *
     * <p>Nothing is a working state, not an error: without one, the rules and the cache still
     * label what they recognise, and everything else stays visible.
     */
    public Optional<ContentClassifier> current() {
        PipelineConfig.Llm llm = config.snapshot().application().llm();
        if (llm == null) {
            return Optional.empty();
        }
        List<String> choices = llm.models() == null ? List.of() : llm.models().scoringChoices();
        if (choices.isEmpty()) {
            return Optional.empty();
        }
        // The configured default and never one the browser named. Which model judges is a
        // parameter of the run because two judges are two scales and the comparison is the
        // point; a label is a fact about a paragraph, so there is nothing to compare and
        // nothing worth letting a request decide.
        String model = choices.getFirst();
        return chatModels.of(llm, model).map(chatModel -> new ContentClassifier(chatModel, model, json));
    }
}
