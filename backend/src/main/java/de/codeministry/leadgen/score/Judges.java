/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.score;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.codeministry.leadgen.config.ConfigRegistry;
import de.codeministry.leadgen.config.model.PipelineConfig;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.AnthropicSetup;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.setup.OpenAiSetup;
import org.springframework.stereotype.Component;

/**
 * Builds the judge the current configuration asks for, or none.
 *
 * <p>Per run rather than once at startup, because the configuration is hot-reloadable: a
 * key added to `.env` and a reload should start producing scores without a restart, and a
 * key removed should stop.
 *
 * <p><b>`provider` is a kind, never a default.</b> It names a wire format and nothing
 * else: two are implemented, and the base URL still decides who answers. No committed file
 * names a vendor's address, and neither does this class — a provider it does not know is
 * refused loudly rather than approximated, because a request in the wrong shape does not
 * fail cleanly. It is answered with a 400, or worse, parsed out of the wrong field into an
 * offer that looks judged and is not.
 */
@Slf4j
@Component
public class Judges {

    private static final String OPENAI_COMPATIBLE = "openai-compatible";
    private static final String OLLAMA = "ollama";
    private static final String ANTHROPIC = "anthropic";

    /**
     * Its own mapper, not the web one. This reads a model's answer, which is plain JSON
     * with none of the conventions the HTTP layer is configured for, and depending on an
     * auto-configured bean would tie a scoring request to how the API happens to
     * serialise.
     */
    private final ObjectMapper json = new ObjectMapper();

    /** The same ceiling the batched request carries, for the same reason it does. */
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    /**
     * One built model per distinct configuration, and it is the price of the hot-reload
     * requirement.
     *
     * <p>The judge is a parameter of the run, so `current()` is called once per run and would
     * otherwise build a fresh HTTP client — connection pool, dispatcher threads and all —
     * every time. Keyed on everything that changes who answers and how, so a key added to
     * `.env` at five in the afternoon still produces a new client on the next run rather
     * than a cached one pointed at the old address.
     *
     * <p>Unbounded on purpose: the keys come from a configuration file, so the set is as
     * large as the number of models somebody has configured, which is single digits.
     */
    private final Map<String, ChatModel> models = new ConcurrentHashMap<>();

    private final ConfigRegistry config;

    Judges(ConfigRegistry config) {
        this.config = config;
    }

    /** The judge the configuration names by default. */
    public Optional<Judge> current() {
        return current(null);
    }

    /** Every model that may be asked, the configured default first. Empty when none is set. */
    public List<String> choices() {
        PipelineConfig.Llm llm = config.snapshot().application().llm();
        return llm == null || llm.models() == null ? List.of() : llm.models().scoringChoices();
    }

    /**
     * The same allowlist check {@link #current(String)} makes, without building anything.
     *
     * <p>It exists so a run can refuse before it starts. Scoring is the last stage, so a
     * name checked only there is checked after the sources have been read, the duplicates
     * clustered, the filter applied and the surviving ads fetched from their portals —
     * measured: a request naming a model nobody configured got a 400 having already
     * ingested and re-filtered the whole standing corpus. The pass is wasted, the portals
     * were asked for nothing, and the answer says only that the model is unknown.
     *
     * @throws UnknownModel when {@code requested} is not among {@link #choices()}.
     */
    public void check(String requested) {
        if (blank(requested)) {
            return;
        }
        List<String> choices = choices();
        if (!choices.contains(requested.trim())) {
            throw new UnknownModel(requested.trim(), choices);
        }
    }

    /**
     * The judge for one named model, or the configured default when nothing is named.
     *
     * <p><b>An unrecognised name is refused, never forwarded.</b> The name arrives as a
     * request parameter, and the endpoint it would reach is billed per token: passing it
     * through means an arbitrary string deciding what gets bought. It is also the cheaper
     * failure by far — a wrong model that the provider happens to accept answers, scores,
     * and writes itself into `score_model`, where it looks exactly like a deliberate
     * comparison.
     *
     * @throws UnknownModel when {@code requested} is not among {@link #choices()}.
     */
    public Optional<Judge> current(String requested) {
        PipelineConfig.Llm llm = config.snapshot().application().llm();
        if (llm == null || blank(llm.provider())) {
            return Optional.empty();
        }
        // A key is what a hosted provider needs and a local one does not. Requiring it
        // everywhere made `provider: ollama` unusable: a local server wants no key, so
        // there was nothing to write in `.env`, and the judge was silently never built.
        if (blank(llm.apiKey()) && !OLLAMA.equals(llm.provider())) {
            return Optional.empty();
        }
        List<String> choices = llm.models() == null ? List.of() : llm.models().scoringChoices();
        if (choices.isEmpty()) {
            log.warn("llm.models.scoring is not set; nothing can be scored");
            return Optional.empty();
        }
        // The default is the first entry, so "nothing was asked for" and "the configured
        // one was asked for" are the same case and cannot drift apart.
        String model = blank(requested) ? choices.getFirst() : requested.trim();
        if (!choices.contains(model)) {
            throw new UnknownModel(model, choices);
        }
        if (blank(llm.baseUrl())) {
            // Required even for a hosted provider whose address never changes: a URL in
            // the code is a vendor in the code, and this repository has none.
            log.warn("llm.base_url is not set; there is nowhere to send a scoring request");
            return Optional.empty();
        }
        return switch (llm.provider()) {
            // Ollama serves the same chat-completions shape under /v1, so it is the same
            // judge with a different address. It is listed separately because it is the
            // one provider that needs no key, and that is a rule about the value.
            case OPENAI_COMPATIBLE, OLLAMA ->
                Optional.of(new ChatClientJudge(openAi(llm.baseUrl(), key(llm), model), model, json, bounds()));
            // The only provider with a batch endpoint, which is why it is the only one that
            // gets a judge of its own. Its base URL and key are handed over twice: once to
            // the chat model, and once to the batch half, which is still hand-rolled HTTP.
            case ANTHROPIC ->
                Optional.of(new AnthropicJudge(
                        anthropic(llm.baseUrl(), key(llm), model), llm.baseUrl(), key(llm), model, json, bounds()));
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
     * A model nobody configured. A sentence rather than a stack trace, because it reaches a
     * select box: the browser holds its choice in localStorage, so a name that was valid
     * yesterday and was removed from `.env` overnight arrives here on the next click.
     */
    public static class UnknownModel extends RuntimeException {
        UnknownModel(String requested, List<String> choices) {
            super("'%s' is not a configured scoring model; configured are %s"
                    .formatted(requested, String.join(", ", choices)));
        }
    }

    /**
     * The chat model for the OpenAI-compatible wire format.
     *
     * <p>An <em>empty</em> key rather than a null one is what puts the client into its
     * no-auth mode, which is what a local server wants — the same rule `key` already
     * encodes, now with a second reader.
     */
    private ChatModel openAi(String baseUrl, String apiKey, String model) {
        return models.computeIfAbsent(
                cacheKey(OPENAI_COMPATIBLE, baseUrl, apiKey, model), ignored -> OpenAiChatModel.builder()
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
     * <p>`maxTokens` is set here and not left to a default because on the current models
     * reasoning is counted against it before the text begins: a ceiling sized to the few
     * lines of JSON this asks for truncates the answer before the answer starts, and a
     * truncated body parses to no reasons at all.
     */
    private ChatModel anthropic(String baseUrl, String apiKey, String model) {
        return models.computeIfAbsent(
                cacheKey(ANTHROPIC, baseUrl, apiKey, model), ignored -> AnthropicChatModel.builder()
                        .anthropicClient(AnthropicSetup.setupSyncClient(baseUrl, apiKey, TIMEOUT, 0, null, null))
                        // Same reason as the OpenAI pair above: the builder would otherwise
                        // construct an asynchronous client from nothing.
                        .anthropicClientAsync(AnthropicSetup.setupAsyncClient(baseUrl, apiKey, TIMEOUT, 0, null, null))
                        .options(AnthropicChatOptions.builder()
                                .model(model)
                                .maxTokens(AnthropicJudge.MAX_TOKENS)
                                .build())
                        .build());
    }

    /** The key is hashed rather than kept, so a heap dump does not hand out the API key. */
    private static String cacheKey(String provider, String baseUrl, String apiKey, String model) {
        return provider + '\u0000' + baseUrl + '\u0000' + Integer.toHexString(apiKey.hashCode()) + '\u0000' + model;
    }

    /** The weight table this run judges against, or nothing when none is configured. */
    private Map<String, Integer> bounds() {
        var rules = config.snapshot().rules();
        return ChatClientJudge.boundsOf(rules == null ? null : rules.scoring());
    }

    /** Empty rather than null, so a local server gets a harmless header instead of "null". */
    private static String key(PipelineConfig.Llm llm) {
        return llm.apiKey() == null ? "" : llm.apiKey();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
