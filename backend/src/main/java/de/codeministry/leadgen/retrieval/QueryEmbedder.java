/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.retrieval;

import de.codeministry.leadgen.config.ConfigRegistry;
import de.codeministry.leadgen.config.model.PipelineConfig;
import de.codeministry.leadgen.llm.EmbeddingModels;
import de.codeministry.leadgen.llm.LlmBudget;
import de.codeministry.leadgen.llm.Vectors;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.stereotype.Component;

/**
 * A search phrase as a vector, which is the one model call the read side makes.
 *
 * <p><b>A model call on a read path is a first for this codebase, and it is why this class
 * exists rather than three lines inside the query service.</b> Everything else that leaves for
 * a model does so on a nightly pass with a budget sized for a nightly pass. A search box calls
 * this once per submitted query, from a screen somebody is typing on, against the same three
 * hundred calls a day the judge spends — so the two defences are here, in one place, where they
 * can be read together:
 *
 * <ul>
 *   <li><b>A cache keyed by the model and the normalised phrase.</b> Searching the same words
 *       twice, or reloading a saved view, costs nothing. Small and bounded, because a person
 *       types a handful of distinct phrases in a session and an unbounded map on a long-running
 *       process is a leak with a slow fuse.
 *   <li><b>{@link LlmBudget#take()} before every miss.</b> A refusal is not an error here: the
 *       caller turns it into a sentence in place of the list, and the reader can see that the
 *       day is spent rather than that the market is quiet.
 * </ul>
 *
 * <p>The debounce belongs on the other side of the wire, because the cheapest request is the one
 * the browser never sends.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QueryEmbedder {

    /**
     * How many distinct phrases are remembered. A session's worth and then some; past it the
     * oldest goes, so a process that runs for weeks does not accumulate every phrase ever typed.
     */
    private static final int REMEMBERED = 256;

    private final ConfigRegistry config;
    private final EmbeddingModels models;
    private final LlmBudget budget;

    /**
     * Access-ordered and bounded, guarded by {@code this} rather than a concurrent map: the
     * eviction and the lookup have to be one decision, and a request that reaches here has
     * already decided to wait on a model.
     */
    private final Map<String, float[]> remembered = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, float[]> eldest) {
            return size() > REMEMBERED;
        }
    };

    /**
     * The phrase as a vector literal ready for {@code CAST(… AS vector)}, or nothing.
     *
     * <p>Empty means the phrase could not be embedded now — no model, an endpoint that did not
     * answer, a vector of the wrong width, or a spent budget. The caller decides what to say;
     * what it must not do is quietly widen the list, because a filter that silently does
     * nothing makes the count beside it a lie.
     */
    public Optional<String> vectorFor(String phrase, String model) {
        return vectorFor(phrase, model, budget::take);
    }

    /**
     * The same, asking {@code take} rather than {@code llm.budget} before a request. The chat's
     * semantic search passes its own ceiling here: a question typed into the drawer is not a stage
     * of a run and must neither spend nor be refused by the pipeline's day (ISC-433). The cache is
     * shared either way, since a vector does not care who paid for it.
     */
    public Optional<String> vectorFor(String phrase, String model, BooleanSupplier take) {
        String key = model + '\0' + phrase.strip().toLowerCase(Locale.ROOT);
        synchronized (this) {
            float[] cached = remembered.get(key);
            if (cached != null) {
                return Optional.of(Vectors.literal(cached));
            }
        }

        PipelineConfig.Llm llm = config.snapshot().application().llm();
        var embeddings = models.of(llm, model);
        if (embeddings.isEmpty()) {
            return Optional.empty();
        }
        if (!take.getAsBoolean()) {
            log.info("Search: the day's budget is spent, so '{}' cannot be embedded", phrase);
            return Optional.empty();
        }

        float[] vector;
        try {
            vector = embeddings
                    .get()
                    .call(new EmbeddingRequest(java.util.List.of(phrase.strip()), null))
                    .getResults()
                    .getFirst()
                    .getOutput();
        } catch (RuntimeException e) {
            log.warn("Embedding the search phrase failed: {}", e.getMessage());
            return Optional.empty();
        }
        if (vector.length < Vectors.DIMENSIONS) {
            log.warn(
                    "Model '{}' returns {}-dimensional vectors and the column holds {}; the search cannot run",
                    model,
                    vector.length,
                    Vectors.DIMENSIONS);
            return Optional.empty();
        }

        float[] narrowed = Vectors.narrowed(vector);
        synchronized (this) {
            remembered.put(key, narrowed);
        }
        return Optional.of(Vectors.literal(narrowed));
    }
}
