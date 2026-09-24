/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.ingest.extract;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.codeministry.leadgen.config.ConfigRegistry;
import de.codeministry.leadgen.config.model.PipelineConfig;
import de.codeministry.leadgen.llm.ChatModels;
import de.codeministry.leadgen.llm.LlmBudget;
import de.codeministry.leadgen.llm.ModelChoice;
import java.time.Clock;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Builds the extractor the {@code fallback: llm} case may ask, or none.
 *
 * <p>Per run rather than once at startup, the same rule the judge, the classifier and the
 * field extractor follow: the configuration is hot-reloadable, so a key added to `.env` at
 * five in the afternoon works on the next run and not after a restart.
 *
 * <p><b>This is the stage that finally reads {@code llm.models.extraction}.</b> The key has
 * been in the shipped file since the beginning with `# not read yet` beside it, which is the
 * same class of lie as an unimplemented auth mode. It reads the key when it is set and
 * {@code scoring} when it is not, because most installations run one model and there is
 * nothing to write in the second line — the same argument that made a key optional for
 * Ollama. Which one was taken is said once, not per document, and never silently.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LlmExtractors implements ExtractionFallback {

    /**
     * Its own mapper, not the web one, for the same reason the judge, the classifier and the
     * field extractor have theirs: this reads a model's answer, which is plain JSON with none
     * of the conventions the HTTP layer is configured for.
     */
    private final ObjectMapper json = new ObjectMapper();

    /**
     * One log line per process, not one per document. An inbox with thirty pasted adverts in
     * it would otherwise write the same sentence thirty times.
     */
    private final AtomicBoolean saidWhichModel = new AtomicBoolean();

    /**
     * One constructor and no second one taking a {@link Clock}: a bean with two of them is
     * a bean Spring instantiates with neither, and the failure names a missing default
     * constructor rather than the ambiguity. The window a date is checked against is the
     * extractor's, and that is where a test sets it.
     */
    private final Clock clock = Clock.systemDefaultZone();

    private final ConfigRegistry config;
    private final ChatModels chatModels;
    private final LlmBudget budget;

    @Override
    public Optional<LlmExtractor.Reading> read(String document) {
        Optional<LlmExtractor> extractor = current();
        if (extractor.isEmpty()) {
            // The difference that matters to whoever pasted the file: the configuration asks
            // for a reading nobody can deliver, rather than the document being unreadable.
            log.warn("A document has no frontmatter and its source asks for `fallback: llm`,"
                    + " but no model is reachable; the document is left where it is");
            return Optional.empty();
        }
        // After the model is known to exist and never before: a configuration that can reach
        // nothing would otherwise spend a day's allowance on documents it never sends.
        if (!budget.take()) {
            log.warn("today's llm.budget.max_calls_per_day is spent; the document is left where it is");
            return Optional.empty();
        }
        return extractor.get().read(document);
    }

    /**
     * The extractor this run may ask, or nothing when the configuration cannot reach a model.
     */
    private Optional<LlmExtractor> current() {
        PipelineConfig.Llm llm = config.snapshot().application().llm();
        if (llm == null || llm.models() == null) {
            return Optional.empty();
        }
        String model = model(llm.models());
        if (model == null) {
            return Optional.empty();
        }
        return chatModels.of(llm, model).map(chatModel -> new LlmExtractor(chatModel, model, json, clock));
    }

    private String model(PipelineConfig.Llm.Models models) {
        String chosen = modelFor(models);
        if (chosen != null) {
            announce(chosen.equals(models.extraction()) ? "llm.models.extraction" : "llm.models.scoring", chosen);
        }
        return chosen;
    }

    /**
     * {@code extraction} when it names one, {@code scoring} otherwise, and nothing when
     * neither does — which is the fresh clone, where the tool runs without a model at all.
     *
     * <p>Static and public because the Rules screen shows which model would answer, and a
     * second copy of this choice would disagree with the run exactly once: the screen would
     * name the scoring model while the run used the extraction one, and both would look
     * right. The choice itself is {@link ModelChoice#extraction}.
     */
    public static String modelFor(PipelineConfig.Llm.Models models) {
        return ModelChoice.extraction(models).orElse(null);
    }

    private void announce(String key, String model) {
        if (saidWhichModel.compareAndSet(false, true)) {
            log.info("A document with no frontmatter is read by '{}', from {}", model, key);
        }
    }
}
