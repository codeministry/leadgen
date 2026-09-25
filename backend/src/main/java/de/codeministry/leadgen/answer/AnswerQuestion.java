/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.answer;

import de.codeministry.leadgen.offer.BadShortlistRequest;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * The bounded questions the pipeline already asks a model, and which a candidate model can
 * therefore be measured on.
 *
 * <p>An enum for the reason {@link de.codeministry.leadgen.ask.AdvertQuestion} is one: the
 * wire name arrives in a request, the prompt is composed from this side only, and the type is
 * the allowlist. Each constant names a question that has a stored answer to compare against —
 * a question without one would measure nothing.
 */
public enum AnswerQuestion {

    /** The content classifier: which blocks of the advert are content and which are furniture. */
    BLOCKS("blocks"),

    /** The field extractor: start, duration and apply-by, read out of the advert. */
    FIELDS("fields"),

    /** The judge: the factors it scored and the points it gave each. */
    JUDGE("judge");

    private final String key;

    AnswerQuestion(String key) {
        this.key = key;
    }

    /** The name this question travels under, in a request and in the answer. */
    public String key() {
        return key;
    }

    /**
     * The question named, case-insensitively, or a 400 naming the ones that exist.
     *
     * <p>Never a default: a measurement of the wrong question looks exactly as precise as one
     * of the right question.
     */
    public static AnswerQuestion of(String name) {
        if (name == null || name.isBlank()) {
            throw new BadShortlistRequest("name a question; a candidate can be measured on %s".formatted(keys()));
        }
        return Arrays.stream(values())
                .filter(question -> question.key.equalsIgnoreCase(name.trim()))
                .findFirst()
                .orElseThrow(() -> new BadShortlistRequest(
                        "'%s' is not a question a candidate can be measured on; it can be measured on %s"
                                .formatted(name, keys())));
    }

    private static String keys() {
        return Arrays.stream(values()).map(AnswerQuestion::key).collect(Collectors.joining(", "));
    }
}
