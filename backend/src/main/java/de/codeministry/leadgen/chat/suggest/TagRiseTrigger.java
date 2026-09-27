/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.chat.suggest;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The tag that rose most, when its current window of {@code tag_window_days} holds at least
 * {@code tag_rise_min_offers} offers and at least {@code tag_rise_percent} more than the window
 * before. A tag absent before and present now at the minimum counts as a rise.
 *
 * <p><b>Its own read-only SQL</b>, because no read service answers it: the analytics screen's tag
 * list ({@code AnalyticsQueryService}) counts over one fixed published-on window and has no
 * second window to compare against. The query is that list's, split in two by arrival: primaries
 * only, by the moment an offer came in rather than the day it was published.
 */
@Component
@RequiredArgsConstructor
class TagRiseTrigger implements SuggestionTrigger {

    private static final String WINDOWS = """
        SELECT tag,
               count(*) FILTER (WHERE o.ingested_at >= :split) AS current_window,
               count(*) FILTER (WHERE o.ingested_at <  :split) AS previous_window
        FROM offer o, unnest(o.tags) AS tag
        WHERE o.duplicate_of_id IS NULL
          AND o.ingested_at >= :from
        GROUP BY tag
        """;

    private final JdbcClient jdbc;

    @Override
    public String key() {
        return SuggestionService.TAG_RISE;
    }

    @Override
    public Optional<SuggestionCandidate> evaluate(SuggestionScope scope, SuggestionThresholds thresholds) {
        Duration window = Duration.ofDays(thresholds.tagWindowDays());
        Instant split = Instant.now().minus(window);
        return jdbc
                .sql(WINDOWS)
                .param("split", Timestamp.from(split))
                .param("from", Timestamp.from(split.minus(window)))
                .query((rs, index) ->
                        new Window(rs.getString("tag"), rs.getInt("current_window"), rs.getInt("previous_window")))
                .list()
                .stream()
                .filter(w -> w.current() >= thresholds.tagRiseMinOffers())
                .filter(w -> w.current() > w.previous())
                .filter(w -> 100L * w.current() >= (100L + thresholds.tagRisePercent()) * w.previous())
                .max(Comparator.comparingInt((Window w) -> w.current() - w.previous())
                        .thenComparing(Window::tag, Comparator.reverseOrder()))
                .map(w -> SuggestionCandidate.of(
                        key(),
                        w.current(),
                        Map.of(
                                "tag", w.tag(),
                                "previous", Integer.toString(w.previous()),
                                "days", Integer.toString(thresholds.tagWindowDays()))));
    }

    private record Window(String tag, int current, int previous) {}
}
