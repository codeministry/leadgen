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
import de.codeministry.leadgen.config.ConfigProperties;
import de.codeministry.leadgen.config.ConfigRegistry;
import de.codeministry.leadgen.config.model.PipelineConfig;
import de.codeministry.leadgen.llm.Answers;
import de.codeministry.leadgen.llm.ChatModels;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
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
 * the request thread. {@link #phrase} is the follow-ups' path, asked once a turn has finished rather
 * than while the chat opens: a lookup in memory, else a call now.
 *
 * <p><b>Once per snapshot.</b> The caller's key names the data the sentences were found in; the
 * model, the language and the sentences themselves are added here, so a changed count, a switched
 * model or the other language is a new key and a reopened chat with none of those is a map
 * lookup. Each map is cleared when it grows past {@link #CACHE_LIMIT}: its keys come from data
 * snapshots, and an old snapshot is never asked for again. The suggestions' phrasings sit in front
 * of {@link PhrasingStore}, so for them a restart costs a table read rather than a model call
 * (ISC-531). The follow-ups' sit in a map of their own and nowhere else (ISC-539): a finished turn's
 * keys are not asked for again, and sharing one map would let them clear the suggestions' out.
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

    /** The suggestions' phrasings, in front of the table. */
    private final Map<String, Map<String, String>> cache = new ConcurrentHashMap<>();

    /** The follow-ups' phrasings, in memory only (ISC-539). */
    private final Map<String, Map<String, String>> followUps = new ConcurrentHashMap<>();

    /**
     * Keys whose call failed, or whose named model could not be built, and the instant each may be
     * asked again: {@code leadgen.chat.suggestion-retry-after} later (ISC-535). Not retried by
     * itself — the next open or warm-up after that instant asks once more — so a model that is down
     * costs one call per key and window, never a loop (ISC-532). A refused take is not kept here,
     * since it sends nothing and the next day answers it. At {@link #CACHE_LIMIT} the passed windows
     * go first and then the oldest, never the whole map: clearing it would lift every window at
     * once and send a burst of calls to a model that is still down.
     */
    private final Map<String, Instant> skippedUntil = new ConcurrentHashMap<>();

    private final ChatCapability capability;
    private final ChatModels chatModels;
    private final ConfigRegistry config;
    private final ChatBudget budget;
    private final PhrasingStore store;
    private final ConfigProperties properties;

    /**
     * The follow-ups' path: the sentences as the reader sees them, in the order given — the
     * phrasing kept in memory, else one asked for now, else the catalog's.
     *
     * <p>Kept in memory only, never in the table (ISC-539): a follow-up belongs to one finished turn
     * and is never asked for again, so a stored row would only push the suggestions' rows out of
     * the table's bound.
     *
     * @param snapshot  what the sentences were derived from; equal snapshots share one phrasing
     * @param sentences the catalog's sentences by key, in the reader's language
     */
    Map<String, String> phrase(String snapshot, Map<String, String> sentences, String language) {
        if (sentences.isEmpty()) {
            return sentences;
        }
        return key(snapshot, sentences, language)
                .map(followUps::get)
                .or(() -> attempt(snapshot, sentences, language, false))
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
        found.ifPresent(phrased -> remember(cache, key, phrased));
        return found;
    }

    /**
     * Asks the chat's model now and keeps what it answered, in memory and in the table, or a failure
     * under the retry window (ISC-535). Empty when there is no model, the chat's day is spent or the
     * call fails; the caller shows the catalog's sentences then.
     */
    Optional<Map<String, String>> attempt(String snapshot, Map<String, String> sentences, String language) {
        return attempt(snapshot, sentences, language, true);
    }

    private Optional<Map<String, String>> attempt(
            String snapshot, Map<String, String> sentences, String language, boolean persist) {
        Optional<String> modelName = capability.model();
        PipelineConfig.Llm llm = config.snapshot().application().llm();
        if (modelName.isEmpty()) {
            return Optional.empty();
        }
        String key = key(modelName.get(), snapshot, sentences, language);
        Optional<ChatModel> model = chatModels.of(llm, modelName.get());
        if (model.isEmpty()) {
            if (persist) {
                log.warn("The chat model is named but could not be built; the catalog's sentences are shown");
                skip(key);
            } else {
                // Said here too: someone who only reads conversations would otherwise never learn it.
                log.warn("The chat model is named but could not be built; the follow-ups show the catalog's sentences");
            }
            return Optional.empty();
        }
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
            // A follow-up key is never asked again, so it is not kept: it would only crowd the
            // suggestions' entries out of the skip map's bound.
            if (persist) {
                skip(key);
            }
            return Optional.empty();
        }
        Map<String, String> phrased = new LinkedHashMap<>();
        sentences.forEach((item, catalog) -> {
            String candidate = answer.get(item);
            phrased.put(item, acceptable(catalog, candidate) ? candidate.strip() : catalog);
        });
        Map<String, String> result = Collections.unmodifiableMap(phrased);
        if (!persist) {
            remember(followUps, key, result);
            return Optional.of(result);
        }
        remember(cache, key, result);
        try {
            store.save(key, snapshot, language, modelName.get(), sentences, result);
        } catch (RuntimeException e) {
            log.warn("The suggestion phrasing was not stored; it holds until the next restart: {}", e.toString());
        }
        return Optional.of(result);
    }

    /**
     * Whether {@code key} failed, or found its model unbuildable, less than the retry window ago;
     * such a key is neither asked nor queued until the window has passed.
     */
    boolean skipped(String key) {
        Instant until = skippedUntil.get(key);
        if (until == null) {
            return false;
        }
        if (Instant.now().isBefore(until)) {
            return true;
        }
        skippedUntil.remove(key, until);
        return false;
    }

    private void skip(String key) {
        Instant now = Instant.now();
        if (skippedUntil.size() >= CACHE_LIMIT) {
            skippedUntil.values().removeIf(until -> !now.isBefore(until));
        }
        if (skippedUntil.size() >= CACHE_LIMIT) {
            // One window for every key, so the earliest end is the oldest skip.
            skippedUntil.entrySet().stream()
                    .min(Map.Entry.comparingByValue())
                    .ifPresent(oldest -> skippedUntil.remove(oldest.getKey(), oldest.getValue()));
        }
        skippedUntil.put(key, now.plus(properties.chat().suggestionRetryAfter()));
    }

    private static void remember(Map<String, Map<String, String>> map, String key, Map<String, String> phrased) {
        if (map.size() >= CACHE_LIMIT) {
            map.clear();
        }
        map.put(key, phrased);
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
