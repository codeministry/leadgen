/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.offer;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.core.ResolvableType;
import org.springframework.format.support.DefaultFormattingConversionService;
import org.springframework.validation.DataBinder;

/**
 * The shortlist's query string, bound: one field per parameter of {@code GET /api/v1/offers}.
 *
 * <p><b>One binding for the screen and the chat.</b> The controller takes this record as its one
 * argument, so Spring MVC binds the request's parameters onto its constructor; {@link #parse}
 * binds a stored query string onto the same constructor with Spring's {@link DataBinder}, and both
 * resolve it with {@link #query}. A shortlist view pinned in the chat (ISC-452) therefore means
 * what the same query string means on the screen, and there is no second parser to drift: a name
 * added here is bound in both places at once.
 *
 * <p>{@code portal} keeps the singular name it had: {@code ?portal=a} binds to a one-element list,
 * so every link and every bookmark written before the filter took more than one still means what it
 * meant. The booleans and the limit are boxed so a missing parameter binds to null and reads as the
 * default the endpoint always had (false, and 0 for "the screen's page").
 */
public record ShortlistParams(
        String q,
        String band,
        Integer minScore,
        Integer maxScore,
        String scoreState,
        List<String> portal,
        Boolean archived,
        String sort,
        String startWindow,
        String semantic,
        Long similar,
        Integer minMonths,
        Boolean deadlineOpen,
        Boolean possibleDuplicates,
        String topic,
        String cursor,
        Integer limit) {

    /** The conversions MVC's own binder applies to a query parameter: numbers, booleans, comma lists. */
    private static final DefaultFormattingConversionService CONVERSIONS = new DefaultFormattingConversionService();

    /**
     * The query these parameters describe. Resolved here and not inside the query, because this is
     * where a string stops being a string: the enums are the allowlist, so a name nobody defined is
     * refused at the edge with a sentence and never reaches a statement. {@link ScoreFilter} is built
     * here for the same reason one step further on — it refuses two spellings of the score axis
     * before anything has been read.
     */
    public ShortlistQuery query() {
        return new ShortlistQuery(
                q,
                new ScoreFilter(band, minScore, maxScore, ScoreState.of(scoreState)),
                portal,
                Boolean.TRUE.equals(archived),
                ShortlistSort.of(sort),
                StartWindow.of(startWindow),
                new RelatedFilter(semantic, similar),
                minMonths,
                Boolean.TRUE.equals(deadlineOpen),
                Boolean.TRUE.equals(possibleDuplicates),
                topic,
                cursor,
                limit == null ? 0 : limit);
    }

    /**
     * A shortlist query string — {@code band=shortlist&portal=a&portal=b}, with or without a leading
     * {@code ?} — bound the way the endpoint binds its request.
     *
     * @throws IllegalArgumentException when a value does not convert, with the parameter's name
     */
    public static ShortlistParams parse(String queryString) {
        Map<String, List<String>> values = values(queryString);
        DataBinder binder = new DataBinder(null, "shortlist");
        binder.setTargetType(ResolvableType.forClass(ShortlistParams.class));
        binder.setConversionService(CONVERSIONS);
        binder.construct(new DataBinder.ValueResolver() {
            @Override
            public Object resolveValue(String name, Class<?> type) {
                List<String> found = values.get(name);
                // One value as a string, several as an array: what a servlet request hands the MVC
                // binder, so `portal=a,b` splits on the comma exactly as it does on the screen.
                if (found == null) {
                    return null;
                }
                return found.size() == 1 ? found.getFirst() : found.toArray(String[]::new);
            }

            @Override
            public Set<String> getNames() {
                return values.keySet();
            }
        });
        if (binder.getBindingResult().hasErrors() || binder.getTarget() == null) {
            var error = binder.getBindingResult().getFieldError();
            throw new IllegalArgumentException(
                    error == null
                            ? "the shortlist view '" + queryString + "' cannot be read"
                            : "the shortlist view's " + error.getField() + " '" + error.getRejectedValue()
                                    + "' is not a valid value");
        }
        return (ShortlistParams) binder.getTarget();
    }

    /** The query string's parameters, decoded as a servlet container decodes them, in their order. */
    private static Map<String, List<String>> values(String queryString) {
        Map<String, List<String>> values = new LinkedHashMap<>();
        String text = queryString == null ? "" : queryString.strip();
        if (text.startsWith("?")) {
            text = text.substring(1);
        }
        for (String pair : text.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String name = decode(eq < 0 ? pair : pair.substring(0, eq));
            String value = eq < 0 ? "" : decode(pair.substring(eq + 1));
            values.computeIfAbsent(name, key -> new java.util.ArrayList<>()).add(value);
        }
        return values;
    }

    private static String decode(String text) {
        return URLDecoder.decode(text, StandardCharsets.UTF_8);
    }
}
