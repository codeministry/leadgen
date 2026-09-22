/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.packaging;

import de.codeministry.leadgen.config.ConfigRegistry;
import de.codeministry.leadgen.config.model.PipelineConfig;
import de.codeministry.leadgen.config.model.SkillProfile;
import de.codeministry.leadgen.llm.EmbeddingModels;
import de.codeministry.leadgen.llm.LlmBudget;
import de.codeministry.leadgen.llm.Vectors;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.stereotype.Component;

/**
 * The reference projects as vectors, so a cover letter can pitch the ones the advert is
 * actually about.
 *
 * <h2>One vector per project and language, not per project</h2>
 *
 * <p>The pitches are the only fields that say what a project <i>was</i>, and they are content
 * rather than repository language: {@code pitch_de} is German and {@code pitch_en} is English.
 * An advert is compared against the pitch in its own language, because a German pitch embedded
 * against a German advert is the comparison that means something and a mixed-language blob is
 * not. The language is the one {@code PackagingService} already determined for the letter.
 *
 * <h2>No table, and the digest is why</h2>
 *
 * <p>A profile holds a few dozen texts, so one request covers it and the result is remembered
 * in memory. The cache key is the model plus a digest of the text, which makes hot reload free:
 * an edited pitch is a different digest and is re-embedded on its own, while everything else
 * stays. A table would buy persistence across restarts for a handful of vectors that cost one
 * request to rebuild.
 */
@Slf4j
@Component
public class ProfileEmbeddings {

    private final ConfigRegistry config;
    private final EmbeddingModels models;
    private final LlmBudget budget;

    /** Keyed by model and text digest, so an edited pitch invalidates only itself. */
    private final Map<String, float[]> remembered = new ConcurrentHashMap<>();

    ProfileEmbeddings(ConfigRegistry config, EmbeddingModels models, LlmBudget budget) {
        this.config = config;
        this.models = models;
        this.budget = budget;
    }

    /**
     * A vector per reference project, in the language the letter will be written in.
     *
     * <p>Empty when this installation cannot embed, which is the keyless path: the caller then
     * falls back to counting stack tokens, exactly as it did before this existed. It is also
     * empty when the day's budget is spent — a packaging run that silently pitched worse
     * projects would be harder to notice than one that pitched the same ones as yesterday.
     */
    public Map<String, float[]> forLanguage(SkillProfile profile, String language) {
        if (profile == null
                || profile.referenceProjects() == null
                || profile.referenceProjects().isEmpty()) {
            return Map.of();
        }
        String model = model();
        if (model == null) {
            return Map.of();
        }

        Map<String, String> texts = new LinkedHashMap<>();
        for (SkillProfile.ReferenceProject project : profile.referenceProjects()) {
            String text = text(project, language);
            if (!text.isBlank()) {
                texts.put(project.id(), text);
            }
        }

        Map<String, float[]> vectors = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        List<String> missingIds = new ArrayList<>();
        for (var entry : texts.entrySet()) {
            float[] cached = remembered.get(key(model, entry.getValue()));
            if (cached != null) {
                vectors.put(entry.getKey(), cached);
            } else {
                missingIds.add(entry.getKey());
                missing.add(entry.getValue());
            }
        }
        if (missing.isEmpty()) {
            return vectors;
        }

        var embeddings = models.of(config.snapshot().application().llm(), model);
        if (embeddings.isEmpty() || !budget.take()) {
            // Whatever was already cached still counts; the rest simply has no vector and the
            // ranking falls back for those projects rather than for the whole letter.
            return vectors;
        }
        try {
            List<float[]> answered = embeddings.get().call(new EmbeddingRequest(missing, null)).getResults().stream()
                    .map(result -> result.getOutput())
                    .toList();
            if (answered.size() != missing.size()) {
                log.warn(
                        "Asked for {} profile embeddings and got {}; the letter keeps the lexical choice",
                        missing.size(),
                        answered.size());
                return vectors;
            }
            for (int index = 0; index < missing.size(); index++) {
                float[] vector = answered.get(index);
                if (vector.length < Vectors.DIMENSIONS) {
                    log.warn(
                            "Model '{}' returns {} dimensions and the offer vectors hold {};"
                                    + " the letter keeps the lexical choice",
                            model,
                            vector.length,
                            Vectors.DIMENSIONS);
                    return vectors;
                }
                float[] narrowed = Vectors.narrowed(vector);
                remembered.put(key(model, missing.get(index)), narrowed);
                vectors.put(missingIds.get(index), narrowed);
            }
        } catch (RuntimeException e) {
            log.warn("Embedding the reference projects failed: {}", e.getMessage());
        }
        return vectors;
    }

    /**
     * What a reference project is, as one text.
     *
     * <p>Title, role and stack are what it was built with; the pitch is what it was about, and
     * it is the half the lexical selection cannot see at all. Title and pitch are chosen
     * through {@link ProjectView}, which is the same choice the letter makes — an embedding
     * of a German title against a German advert has to compare the text that will actually
     * be sent, not the other language's copy of it.
     */
    static String text(SkillProfile.ReferenceProject project, String language) {
        String title = ProjectView.title(project, language);
        StringBuilder text = new StringBuilder(title == null ? "" : title);
        if (project.role() != null && !project.role().isBlank()) {
            text.append('\n').append(project.role().strip());
        }
        if (project.stack() != null && !project.stack().isEmpty()) {
            text.append('\n').append(String.join(", ", project.stack()));
        }
        String pitch = ProjectView.pitch(project, language);
        if (pitch != null) {
            text.append('\n').append(pitch);
        }
        return text.toString().strip();
    }

    private String model() {
        PipelineConfig.Llm llm = config.snapshot().application().llm();
        if (llm == null || llm.models() == null) {
            return null;
        }
        String model = llm.models().embedding();
        return model == null || model.isBlank() ? null : model;
    }

    private static String key(String model, String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return model + ' ' + HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every JRE", e);
        }
    }
}
