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
 * <p><b>Once per snapshot.</b> The caller's key names the data the sentences were found in; the
 * model, the language and the sentences themselves are added here, so a changed count, a switched
 * model or the other language is a new key and a reopened chat with none of those is a map
 * lookup. The map is cleared when it grows past {@link #CACHE_LIMIT}: its keys come from data
 * snapshots, and an old snapshot is never asked for again.
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

    private final ChatCapability capability;
    private final ChatModels chatModels;
    private final ConfigRegistry config;
    private final ChatBudget budget;

    /**
     * The sentences as the reader sees them, in the order given.
     *
     * @param snapshot  what the sentences were derived from; equal snapshots share one phrasing
     * @param sentences the catalog's sentences by key, in the reader's language
     */
    Map<String, String> phrase(String snapshot, Map<String, String> sentences, String language) {
        if (sentences.isEmpty()) {
            return sentences;
        }
        Optional<String> modelName = capability.model();
        PipelineConfig.Llm llm = config.snapshot().application().llm();
        Optional<ChatModel> model = modelName.flatMap(name -> chatModels.of(llm, name));
        if (model.isEmpty()) {
            return sentences;
        }
        String key = String.join("\n", modelName.get(), language, snapshot, sentences.toString());
        Map<String, String> cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        if (!budget.take()) {
            return sentences;
        }
        Map<String, String> answer;
        try {
            answer = ask(model.get(), llm, sentences, language);
        } catch (RuntimeException e) {
            log.warn(
                    "The chat model could not phrase the suggestions; the catalog's sentences are shown: {}",
                    e.toString());
            return sentences;
        }
        Map<String, String> phrased = new LinkedHashMap<>();
        sentences.forEach((item, catalog) -> {
            String candidate = answer.get(item);
            phrased.put(item, acceptable(catalog, candidate) ? candidate.strip() : catalog);
        });
        if (cache.size() >= CACHE_LIMIT) {
            cache.clear();
        }
        Map<String, String> result = Collections.unmodifiableMap(phrased);
        cache.put(key, result);
        return result;
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
