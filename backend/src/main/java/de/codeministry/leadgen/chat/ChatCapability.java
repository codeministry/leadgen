/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.chat;

import de.codeministry.leadgen.config.ConfigRegistry;
import de.codeministry.leadgen.config.model.PipelineConfig;
import de.codeministry.leadgen.llm.ModelChoice;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Whether this instance has a chat at all, and with which model.
 *
 * <p>Read from the live snapshot on every call rather than once at startup, so a model named in a
 * reloaded {@code pipeline.yaml} brings the button back without a restart — and a model removed
 * takes it away, which is the half that matters: a button that opens onto a missing model is a
 * promise the tool cannot keep.
 */
@Component
@RequiredArgsConstructor
public class ChatCapability {

    private final ConfigRegistry config;

    /** The model a turn asks, by {@link ModelChoice#chat}; empty means the chat is absent. */
    public Optional<String> model() {
        PipelineConfig.Llm llm = config.snapshot().application().llm();
        return ModelChoice.chat(llm == null ? null : llm.models());
    }

    public ChatCapabilityView view() {
        return new ChatCapabilityView(model().isPresent());
    }
}
