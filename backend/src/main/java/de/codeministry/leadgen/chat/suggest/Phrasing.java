/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.chat.suggest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.codeministry.leadgen.chat.ChatBudget;
import de.codeministry.leadgen.chat.ChatCapability;
import de.codeministry.leadgen.config.ConfigRegistry;
import de.codeministry.leadgen.config.model.PipelineConfig;
import de.codeministry.leadgen.llm.Answers;
import de.codeministry.leadgen.llm.ChatModels;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Asks the chat's model to phrase the catalog's sentences in the reader's language (ISC-456).
 *
 * <p><b>Paid from the chat's day, never from {@code llm.budget}.</b> One {@link ChatBudget#take()}
 * per model call; a refused take, a missing model and a failed call all answer the catalog's own
 * sentences, so the empty chat is never empty because a model was not there.
 *
 * <p><b>Looked up apart from asked (ISC-527).</b> {@link #stored} never calls a model, so the empty
 * chat answers at once; {@link #attempt} is the call itself, which {@link SuggestionWarmup} makes off
 * the request thread. {@link #phrase} does both in a row and stays for the follow-ups, which are
 * asked once a turn has finished rather than while the chat opens.
 *
 * <p><b>Once per snapshot.</b> The caller's key names the data the sentences were found in; the
 * model, the language and the sentences themselves are added here, so a changed count, a switched
 * model or the other language is a new key and a reopened chat with none of those is a map
 * lookup. The map is cleared when it grows past {@link #CACHE_LIMIT}: its keys come from data
 * snapshots, and an old snapshot is never asked for again. Behind the map sits
 * {@link PhrasingStore}, so a restart costs a table read rather than a model call (ISC-531).
 *
 * <p><b>A phrasing may change the words, not the numbers.</b> A sentence whose digits differ from
 * the catalog's is dropped for the catalog's, so no count and no id reaches the screen that the
 * rules did not find — which is what keeps every id of a follow-up in its turn's ledger.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class Phrasing {

    static final String PROMPT = "leadgen/chat-suggest.st";

    static final int CACHE_LIMIT = 256;

    /** Longer than any catalog sentence by a margin; a model that writes a paragraph is not used. */
    static final int MAX_LENGTH = 300;

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Pattern NUMBER = Pattern.compile("\\d+");

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);

    private static final String TEMPLATE = load();

    private final Map<String, Map<String, String>> cache = new ConcurrentHashMap<>();

    /**
     * Keys whose call failed: not asked again until the data, the model or the language moves the
     * key (ISC-532). A refused take or a missing model is not kept here, since neither sends anything.
     */
    private final Set<String> failed = ConcurrentHashMap.newKeySet();

    private final ChatCapability capability;
    private final ChatModels chatModels;
    private final ConfigRegistry config;
    private final ChatBudget budget;
    private final PhrasingStore store;

    /**
     * The sentences as the reader sees them, in the order given: the stored phrasing, else one
     * asked for now, else the catalog's.
     *
     * @param snapshot  what the sentences were derived from; equal snapshots share one phrasing
     * @param sentences the catalog's sentences by key, in the reader's language
     */
    Map<String, String> phrase(String snapshot, Map<String, String> sentences, String language) {
        if (sentences.isEmpty()) {
            return sentences;
        }
        return key(snapshot, sentences, language)
                .flatMap(this::stored)
                .or(() -> attempt(snapshot, sentences, language))
                .orElse(sentences);
    }

    /**
     * The key a phrasing of these sentences is kept under, or empty when no chat model is named:
     * then there is nothing to phrase and nothing to look up.
     */
    Optional<String> key(String snapshot, Map<String, String> sentences, String language) {
        return capability.model().map(model -> key(model, snapshot, sentences, language));
    }

    /**
     * The phrasing kept under {@code key}, from the map or else from the table; never asks a model.
     * A table that does not answer is a miss, not an error: the caller shows the catalog.
     */
    Optional<Map<String, String>> stored(String key) {
        Map<String, String> cached = cache.get(key);
        if (cached != null) {
            return Optional.of(cached);
        }
        Optional<Map<String, String>> found;
        try {
            found = store.find(key);
        } catch (RuntimeException e) {
            log.warn(
                    "The stored suggestion phrasings could not be read; the catalog's sentences are shown: {}",
                    e.toString());
            return Optional.empty();
        }
        found.ifPresent(phrased -> remember(key, phrased));
        return found;
    }

    /** The language of the newest stored phrasing, if the table holds any. */
    Optional<String> newestLanguage() {
        try {
            return store.newestLanguage();
        } catch (RuntimeException e) {
            log.warn("The stored suggestion phrasings could not be read: {}", e.toString());
            return Optional.empty();
        }
    }

    /**
     * Asks the chat's model now and keeps what it answered. Empty when there is no model, the
     * chat's day is spent or the call fails; the caller shows the catalog's sentences then.
     */
    Optional<Map<String, String>> attempt(String snapshot, Map<String, String> sentences, String language) {
        Optional<String> modelName = capability.model();
        PipelineConfig.Llm llm = config.snapshot().application().llm();
        Optional<ChatModel> model = modelName.flatMap(name -> chatModels.of(llm, name));
        if (model.isEmpty()) {
            return Optional.empty();
        }
        String key = key(modelName.get(), snapshot, sentences, language);
        if (!budget.take()) {
            return Optional.empty();
        }
        Map<String, String> answer;
        try {
            answer = ask(model.get(), llm, sentences, language);
        } catch (RuntimeException e) {
            log.warn(
                    "The chat model could not phrase the suggestions; the catalog's sentences are shown: {}",
                    e.toString());
            if (failed.size() >= CACHE_LIMIT) {
                failed.clear();
            }
            failed.add(key);
            return Optional.empty();
        }
        Map<String, String> phrased = new LinkedHashMap<>();
        sentences.forEach((item, catalog) -> {
            String candidate = answer.get(item);
            phrased.put(item, acceptable(catalog, candidate) ? candidate.strip() : catalog);
        });
        Map<String, String> result = Collections.unmodifiableMap(phrased);
        remember(key, result);
        try {
            store.save(key, snapshot, language, modelName.get(), sentences, result);
        } catch (RuntimeException e) {
            log.warn("The suggestion phrasing was not stored; it holds until the next restart: {}", e.toString());
        }
        return Optional.of(result);
    }

    /** Whether the call for {@code key} failed already; such a key is not asked again. */
    boolean failed(String key) {
        return failed.contains(key);
    }

    private void remember(String key, Map<String, String> phrased) {
        if (cache.size() >= CACHE_LIMIT) {
            cache.clear();
        }
        cache.put(key, phrased);
    }

    private static String key(String model, String snapshot, Map<String, String> sentences, String language) {
        return String.join("\n", model, language, snapshot, sentences.toString());
    }

    /**
     * Streamed and joined rather than called: the chat's turns stream, so this takes the one wire
     * path every configured chat provider is already known to answer on.
     */
    private static Map<String, String> ask(
            ChatModel model, PipelineConfig.Llm llm, Map<String, String> sentences, String language) {
        String prompt;
        try {
            prompt = TEMPLATE.replace("{language}", Catalog.GERMAN.equals(language) ? "German" : "English")
                    .replace(
                            "{questions}", JSON.writerWithDefaultPrettyPrinter().writeValueAsString(sentences));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        Duration timeout = llm == null || llm.timeout() == null ? DEFAULT_TIMEOUT : llm.timeout();
        String text = model.stream(new Prompt(new UserMessage(prompt)))
                .map(Phrasing::textOf)
                .collect(Collectors.joining())
                .block(timeout);
        JsonNode tree;
        try {
            tree = JSON.readTree(Answers.objectIn(text));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("the phrasing is not a JSON object: " + Answers.abbreviate(text), e);
        }
        Map<String, String> answer = new HashMap<>();
        if (tree != null && tree.isObject()) {
            tree.properties().forEach(entry -> {
                if (entry.getValue().isTextual()) {
                    answer.put(entry.getKey(), entry.getValue().asText());
                }
            });
        }
        return answer;
    }

    /** A chunk's text as sent — whitespace included, which {@code Answers.textOf} drops. */
    private static String textOf(ChatResponse response) {
        if (response == null
                || response.getResult() == null
                || response.getResult().getOutput() == null) {
            return "";
        }
        return Objects.toString(response.getResult().getOutput().getText(), "");
    }

    static boolean acceptable(String catalog, String phrased) {
        return phrased != null
                && !phrased.isBlank()
                && phrased.length() <= MAX_LENGTH
                && numbers(phrased).equals(numbers(catalog));
    }

    private static Set<String> numbers(String text) {
        Matcher matcher = NUMBER.matcher(text);
        Set<String> numbers = new java.util.HashSet<>();
        while (matcher.find()) {
            numbers.add(matcher.group());
        }
        return numbers;
    }

    private static String load() {
        try {
            return new ClassPathResource(PROMPT).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("the suggestion prompt is missing: " + PROMPT, e);
        }
    }
}
