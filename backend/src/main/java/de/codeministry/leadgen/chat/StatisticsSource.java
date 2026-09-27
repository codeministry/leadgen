/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.codeministry.leadgen.application.ApplicationStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * What one {@code statistics} call returned, cut down to what an answer draws: a few headline
 * numbers and the intake per day in the window.
 *
 * <p><b>Built from the tool's result and nothing else.</b> The numbers are read out of the JSON the
 * tool returned to the model, before the model wrote a word, and stored on the call's {@code
 * chat_tool_call.data}; the model's text is never parsed for a digit. A table that disagreed with
 * the prose would then be the prose's error, visible as such.
 *
 * <p>The days are {@code YYYY-MM-DD} strings, as the tool wrote them: the record goes through the
 * repository's plain mapper and the web's configured one alike, and a string reads the same in
 * both.
 *
 * @param kind        always {@link ChatSourceKind#STATISTICS}.
 * @param ordinal     the tool call's position within the turn, from 1.
 * @param from        first day of the window the tool applied.
 * @param to          last day of the window the tool applied.
 * @param compareFrom first day of the comparison window, or null without one.
 * @param compareTo   last day of the comparison window, or null without one.
 * @param rows        at most eight headline numbers.
 * @param series      offers that came in per day inside the window, duplicates not counted.
 */
public record StatisticsSource(
        ChatSourceKind kind,
        int ordinal,
        String from,
        String to,
        String compareFrom,
        String compareTo,
        List<Row> rows,
        List<SeriesDay> series)
        implements ChatSourceItem {

    /**
     * One headline number.
     *
     * @param label        what it counts, in English, named by the server.
     * @param value        the number for the window.
     * @param compareValue the same number for the comparison window; null without one, and for a
     *                     state the window does not narrow.
     * @param delta        {@code value} minus {@code compareValue} as the tool computed it; null
     *                     whenever {@code compareValue} is.
     */
    public record Row(String label, int value, Integer compareValue, Integer delta) {}

    /**
     * One day of the intake series.
     *
     * @param day   the day, {@code YYYY-MM-DD}.
     * @param count offers that came in that day, duplicates not counted.
     */
    public record SeriesDay(String day, int count) {}

    private static final ObjectMapper JSON = new ObjectMapper();

    /** The application states that are over; everything else counts as open. */
    private static final Set<String> CLOSED = Set.of(
            ApplicationStatus.WON.name(),
            ApplicationStatus.LOST.name(),
            ApplicationStatus.REJECTED.name(),
            ApplicationStatus.EXPIRED.name());

    /**
     * The source for one call, from the JSON the tool returned; null when that is not a statistics
     * result — the tool failed and returned an error instead.
     */
    public static StatisticsSource of(int ordinal, String result) {
        JsonNode tree;
        try {
            tree = JSON.readTree(result);
        } catch (Exception e) {
            return null;
        }
        if (tree == null || !tree.path("totals").isObject()) {
            return null;
        }
        JsonNode comparison = tree.path("comparison");
        boolean compared = comparison.isObject();
        JsonNode totals = tree.path("totals");
        JsonNode other = comparison.path("totals");
        JsonNode differences = tree.path("differences");
        List<Row> rows = new ArrayList<>();
        rows.add(windowed("Offers in", "primaries", totals, other, differences, compared));
        rows.add(windowed("Duplicates", "duplicates", totals, other, differences, compared));
        rows.add(windowed("Shortlisted", "shortlisted", totals, other, differences, compared));
        rows.add(windowed("Knocked out", "knockouts", totals, other, differences, compared));
        rows.add(windowed("Pipeline runs", "runs", totals, other, differences, compared));
        // States, not histories: the window does not narrow them, so there is nothing to compare.
        rows.add(new Row(
                "Offers in the funnel", tree.path("funnel").path("total").asInt(), null, null));
        rows.add(new Row(
                "Passed the filter", tree.path("funnel").path("survived").asInt(), null, null));
        int open = 0;
        for (JsonNode status : tree.path("applications")) {
            if (!CLOSED.contains(status.path("status").asText())) {
                open += status.path("applications").asInt();
            }
        }
        rows.add(new Row("Applications open", open, null, null));
        List<SeriesDay> series = new ArrayList<>();
        for (JsonNode day : tree.path("intake")) {
            series.add(new SeriesDay(day(day.path("day")), day.path("primaries").asInt()));
        }
        return new StatisticsSource(
                ChatSourceKind.STATISTICS,
                ordinal,
                day(tree.path("from")),
                day(tree.path("to")),
                compared ? day(comparison.path("from")) : null,
                compared ? day(comparison.path("to")) : null,
                List.copyOf(rows),
                List.copyOf(series));
    }

    private static Row windowed(
            String label, String field, JsonNode totals, JsonNode other, JsonNode differences, boolean compared) {
        return new Row(
                label,
                totals.path(field).asInt(),
                compared ? other.path(field).asInt() : null,
                compared ? differences.path(field).asInt() : null);
    }

    /** A day as the tool wrote it: an ISO string, or the {@code [y, m, d]} array of a mapper without the date module. */
    private static String day(JsonNode node) {
        if (node.isArray() && node.size() == 3) {
            return java.time.LocalDate.of(
                            node.get(0).asInt(),
                            node.get(1).asInt(),
                            node.get(2).asInt())
                    .toString();
        }
        return node.isTextual() ? node.asText() : null;
    }
}
