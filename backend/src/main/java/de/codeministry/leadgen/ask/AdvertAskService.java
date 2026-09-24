/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.ask;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.codeministry.leadgen.config.ConfigRegistry;
import de.codeministry.leadgen.config.model.PipelineConfig;
import de.codeministry.leadgen.content.ContentText;
import de.codeministry.leadgen.llm.ChatModels;
import de.codeministry.leadgen.llm.LlmBudget;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * One question, one advert, one model call.
 *
 * <p>On a read path and therefore deliberately small: no stage, no table, no column. The answer
 * lives as long as the screen does, which is what makes it free of the one thing a stored
 * answer cannot avoid — the content stage rewrites an advert when a portal changes its markup,
 * and a remembered answer about the old text is then wrong with a timestamp that says it is
 * fresh.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdvertAskService {

    /** Its own mapper, not the web one: this reads a model's answer, not an HTTP body. */
    private final ObjectMapper json = new ObjectMapper();

    private final ConfigRegistry config;
    private final ChatModels chatModels;
    private final LlmBudget budget;
    private final JdbcClient jdbc;

    /** The questions this installation offers, or none when it cannot ask any. */
    public List<String> questions() {
        return model() == null
                ? List.of()
                : java.util.Arrays.stream(AdvertQuestion.values())
                        .map(AdvertQuestion::key)
                        .toList();
    }

    /**
     * What one advert says about one question.
     *
     * <p>Empty means the question cannot be asked right now: no model, no advert text, or the
     * day's budget is spent. The caller turns that into a sentence rather than into a shrug
     * from the model, because the two read identically on screen and mean opposite things.
     */
    public Optional<AdvertAnswer> ask(long offer, AdvertQuestion question) {
        String model = model();
        if (model == null) {
            return Optional.empty();
        }
        String advert = advert(offer);
        if (advert == null || advert.isBlank()) {
            // Nothing was ever fetched for this offer, so there is no text to read. Asking a
            // model anyway would produce an answer about the newsletter teaser.
            return Optional.empty();
        }
        var chatModel = chatModels.of(config.snapshot().application().llm(), model);
        if (chatModel.isEmpty()) {
            return Optional.empty();
        }
        // One question is one request, counted like a judge's prompt. A reader clicking through
        // five questions on three adverts spends fifteen of the day's calls, which is why the
        // list is fixed and short rather than a text box.
        if (!budget.take()) {
            log.info("Asking about offer {} was refused: the day's llm.budget is spent", offer);
            return Optional.empty();
        }
        return Optional.of(new AdvertAsker(chatModel.get(), model, json).ask(advert, question));
    }

    /**
     * The advert as {@code ContentText} builds it: the CONTENT blocks when it was segmented,
     * {@code full_text} when it was not, and nothing when it was never fetched.
     *
     * <p>Read with a {@code RowMapper} and {@code getString}, never {@code listOfRows()} — a
     * {@code jsonb} column arrives as a {@code PGobject} and the cast that looks right is a 500.
     */
    private String advert(long offer) {
        return jdbc.sql("SELECT content_blocks, full_text FROM offer WHERE id = :id")
                .param("id", offer)
                .query((rs, row) -> ContentText.of(rs.getString("content_blocks"), rs.getString("full_text")))
                .optional()
                .orElse(null);
    }

    /**
     * {@code llm.models.scoring}, the key the judge, the classifier and the field extractor
     * already share. A {@code models.qa} key would be a fourth allowlist for a bounded question
     * answered in three lines of JSON, which is what was refused twice before.
     */
    private String model() {
        PipelineConfig.Llm llm = config.snapshot().application().llm();
        if (llm == null || llm.models() == null) {
            return null;
        }
        String model = llm.models().scoring();
        return model == null || model.isBlank() ? null : model;
    }
}
