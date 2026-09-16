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
import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.setup.OpenAiSetup;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Builds the embedding model a provider kind asks for, and caches it. {@link ChatModels}
 * for the other half, with the same three rules about what is reachable.
 *
 * <p><b>Not every provider has one.</b> The Messages API answers questions and does not
 * embed, so {@code provider: anthropic} is refused by name rather than approximated with a
 * different vendor's endpoint — which is what "the base URL decides who answers" would
 * otherwise quietly do.
 */
@Slf4j
@Component
public class EmbeddingModels {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private static final char SEPARATOR = ' ';

    /**
     * One built model per distinct configuration, for the reason {@link ChatModels} states:
     * the pass is built per run so a key added to `.env` works without a restart, and a fresh
     * HTTP client per run would mean a fresh connection pool every night.
     */
    private final Map<String, EmbeddingModel> models = new ConcurrentHashMap<>();

    /**
     * The embedding model for {@code model} under this configuration, or nothing.
     *
     * <p>Every refusal is {@code Optional.empty()}: without one, deduplication keeps its
     * deterministic strategy and the tool runs, only weaker.
     */
    public Optional<EmbeddingModel> of(PipelineConfig.Llm llm, String model) {
        if (!Providers.reachable(llm, model)) {
            return Optional.empty();
        }
        return switch (llm.provider()) {
            // Ollama serves `/v1/embeddings` in the same shape, so it is the same client at a
            // different address — the same reason the two share a chat client.
            case ChatModels.OPENAI_COMPATIBLE, ChatModels.OLLAMA ->
                Optional.of(openAi(llm.baseUrl(), ChatModels.key(llm), model));
            case ChatModels.ANTHROPIC -> {
                log.warn(
                    "llm.provider is '{}', which has no embedding endpoint;"
                        + " deduplication keeps its deterministic strategy",
                    ChatModels.ANTHROPIC);
                yield Optional.empty();
            }
            default -> {
                log.warn(
                    "llm.provider is '{}'; embeddings are implemented for '{}' and '{}'",
                    llm.provider(),
                    ChatModels.OPENAI_COMPATIBLE,
                    ChatModels.OLLAMA);
                yield Optional.empty();
            }
        };
    }

    private EmbeddingModel openAi(String baseUrl, String apiKey, String model) {
        return models.computeIfAbsent(
            baseUrl + SEPARATOR + Integer.toHexString(apiKey.hashCode()) + SEPARATOR + model,
            ignored -> OpenAiEmbeddingModel.builder()
                // No asynchronous client beside it, unlike the chat pair: this builder
                // constructs only the one it is given and never reaches for a second.
                .openAiClient(OpenAiSetup.setupSyncClient(
                    baseUrl,
                    apiKey,
                    null,
                    null,
                    null,
                    null,
                    false,
                    false,
                    model,
                    TIMEOUT,
                    0,
                    null,
                    null,
                    ObservationRegistry.NOOP,
                    null,
                    List.of()))
                .options(OpenAiEmbeddingOptions.builder().model(model).build())
                .build());
    }
}
