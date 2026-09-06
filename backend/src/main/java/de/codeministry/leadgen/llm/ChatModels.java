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
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.AnthropicSetup;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.setup.OpenAiSetup;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Builds the chat model a provider kind asks for, and caches it.
 *
 * <p>Lifted out of {@code score.Judges} when a second stage needed to ask a model a
 * question. Both traps below produce a failure that names something other than the cause,
 * and a second copy of this code would walk into them again: this is the one place that
 * knows how a client is constructed.
 *
 * <p><b>{@code provider} is a kind, never a default.</b> It names a wire format and nothing
 * else; the base URL still decides who answers. A provider this does not know is refused
 * loudly rather than approximated, because a request in the wrong shape does not fail
 * cleanly — it comes back a 400, or is parsed out of a field that is not there.
 */
@Slf4j
@Component
public class ChatModels {

    public static final String OPENAI_COMPATIBLE = "openai-compatible";
    public static final String OLLAMA = "ollama";
    public static final String ANTHROPIC = "anthropic";

    /**
     * Sized for reasoning-before-text: on the current models the thinking is counted against
     * this before the answer begins, so a ceiling sized to the few lines of JSON that is
     * actually wanted truncates the answer before it starts.
     */
    public static final int MAX_TOKENS = 4096;

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    /**
     * The separator in a cache key. A space, because none of the four parts can hold one: a
     * provider kind is an identifier, a URL cannot carry a raw space, the middle part is hex,
     * and a model name is a wire identifier.
     */
    private static final char SEPARATOR = ' ';

    /**
     * One built model per distinct configuration, and it is the price of the hot-reload
     * requirement: the judge and the classifier are both built per run, and would otherwise
     * construct a fresh HTTP client — connection pool, dispatcher threads and all — every
     * time. Keyed on everything that changes who answers and how, so a key added to `.env`
     * at five in the afternoon still produces a new client on the next run rather than a
     * cached one pointed at the old address.
     *
     * <p>Unbounded on purpose: the keys come from a configuration file, so the set is as
     * large as the number of models somebody has configured, which is single digits.
     */
    private final Map<String, ChatModel> models = new ConcurrentHashMap<>();

    /**
     * The chat model for {@code model} under this configuration, or nothing when the
     * configuration cannot reach one.
     *
     * <p>Every refusal is {@code Optional.empty()} and a WARN rather than an exception: a
     * missing key must leave the tool running, only weaker.
     */
    public Optional<ChatModel> of(PipelineConfig.Llm llm, String model) {
        if (llm == null || blank(llm.provider()) || blank(model)) {
            return Optional.empty();
        }
        // A key is what a hosted provider needs and a local one does not. Requiring it
        // everywhere made `provider: ollama` unusable: a local server wants no key, so there
        // was nothing to write in `.env`, and nothing was ever built.
        if (blank(llm.apiKey()) && !OLLAMA.equals(llm.provider())) {
            return Optional.empty();
        }
        if (blank(llm.baseUrl())) {
            // Required even for a hosted provider whose address never changes: a URL in the
            // code is a vendor in the code, and this repository has none.
            log.warn("llm.base_url is not set; there is nowhere to send a request");
            return Optional.empty();
        }
        return switch (llm.provider()) {
            // Ollama serves the same chat-completions shape under /v1, so it is the same
            // client at a different address. It is listed separately because it is the one
            // provider that needs no key, and that is a rule about the value.
            case OPENAI_COMPATIBLE, OLLAMA -> Optional.of(openAi(llm.baseUrl(), key(llm), model));
            case ANTHROPIC -> Optional.of(anthropic(llm.baseUrl(), key(llm), model));
            default -> {
                log.warn(
                    "llm.provider is '{}'; implemented are '{}', '{}' and '{}'",
                    llm.provider(),
                    OPENAI_COMPATIBLE,
                    OLLAMA,
                    ANTHROPIC);
                yield Optional.empty();
            }
        };
    }

    /**
     * The chat model for the OpenAI-compatible wire format.
     *
     * <p>An <em>empty</em> key rather than a null one is what puts the client into its
     * no-auth mode, which is what a local server wants.
     */
    private ChatModel openAi(String baseUrl, String apiKey, String model) {
        return models.computeIfAbsent(
            cacheKey(OPENAI_COMPATIBLE, baseUrl, apiKey, model),
            ignored -> OpenAiChatModel.builder()
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
                // Both clients, and the asynchronous one is not optional: left unset, the
                // builder makes its own from its own empty fields and fails with "at least
                // one credential source must be specified" — a credential error naming a key
                // that was in fact supplied, for a client nothing here ever calls.
                .openAiClientAsync(OpenAiSetup.setupAsyncClient(
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
                .options(OpenAiChatOptions.builder().model(model).build())
                .build());
    }

    /**
     * The chat model for the Messages API.
     *
     * <p>{@code maxTokens} is set here and not left to a default for the reason
     * {@link #MAX_TOKENS} states: a truncated body parses to nothing at all.
     */
    private ChatModel anthropic(String baseUrl, String apiKey, String model) {
        return models.computeIfAbsent(
            cacheKey(ANTHROPIC, baseUrl, apiKey, model),
            ignored -> AnthropicChatModel.builder()
                .anthropicClient(AnthropicSetup.setupSyncClient(baseUrl, apiKey, TIMEOUT, 0, null, null))
                // Same reason as the OpenAI pair above: the builder would otherwise
                // construct an asynchronous client from nothing.
                .anthropicClientAsync(AnthropicSetup.setupAsyncClient(baseUrl, apiKey, TIMEOUT, 0, null, null))
                .options(AnthropicChatOptions.builder()
                    .model(model)
                    .maxTokens(MAX_TOKENS)
                    .build())
                .build());
    }

    /**
     * The key is hashed rather than kept, so a heap dump does not hand out the API key.
     */
    private static String cacheKey(String provider, String baseUrl, String apiKey, String model) {
        return provider + SEPARATOR + baseUrl + SEPARATOR + Integer.toHexString(apiKey.hashCode()) + SEPARATOR + model;
    }

    /**
     * Empty rather than null, so a local server gets a harmless header instead of "null".
     */
    public static String key(PipelineConfig.Llm llm) {
        return llm.apiKey() == null ? "" : llm.apiKey();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
