/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.chat.suggest;

import de.codeministry.leadgen.config.ConfigRegistry;
import de.codeministry.leadgen.config.model.PipelineConfig;

/**
 * The {@code chat.suggestions.*} lines with every absent one resolved to its shipped default.
 *
 * <p>The defaults are starting values, not measurements (spec 020, Not yet specified); T62 reads
 * the corpus for them. Read per call from the live snapshot, so a reload moves the next set.
 */
public record SuggestionThresholds(
        int newOffersMin,
        int deadlineDays,
        int noReplyDays,
        int tagWindowDays,
        int tagRisePercent,
        int tagRiseMinOffers) {

    public static final int DEFAULT_NEW_OFFERS_MIN = 1;
    public static final int DEFAULT_DEADLINE_DAYS = 7;
    public static final int DEFAULT_NO_REPLY_DAYS = 14;
    public static final int DEFAULT_TAG_WINDOW_DAYS = 7;
    public static final int DEFAULT_TAG_RISE_PERCENT = 30;
    public static final int DEFAULT_TAG_RISE_MIN_OFFERS = 5;

    static SuggestionThresholds of(ConfigRegistry config) {
        PipelineConfig.Chat chat = config.snapshot().application().chat();
        PipelineConfig.Suggestions lines = chat == null ? null : chat.suggestions();
        if (lines == null) {
            lines = new PipelineConfig.Suggestions(null, null, null, null, null, null);
        }
        return new SuggestionThresholds(
                or(lines.newOffersMin(), DEFAULT_NEW_OFFERS_MIN),
                or(lines.deadlineDays(), DEFAULT_DEADLINE_DAYS),
                or(lines.noReplyDays(), DEFAULT_NO_REPLY_DAYS),
                or(lines.tagWindowDays(), DEFAULT_TAG_WINDOW_DAYS),
                or(lines.tagRisePercent(), DEFAULT_TAG_RISE_PERCENT),
                or(lines.tagRiseMinOffers(), DEFAULT_TAG_RISE_MIN_OFFERS));
    }

    private static int or(Integer configured, int fallback) {
        return configured == null ? fallback : configured;
    }
}
