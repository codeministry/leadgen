/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.ask;

import de.codeministry.leadgen.offer.BadShortlistRequest;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * What may be asked of an advert.
 *
 * <h2>A fixed list, and the type is the allowlist</h2>
 *
 * <p>Same reason {@link de.codeministry.leadgen.offer.ShortlistSort} is an enum rather than a
 * validated string: the wire name arrives in a request and the prompt is composed from this
 * side only, so nothing a caller sends ever reaches a model. A free-text box would put the
 * caller's words into the instruction, which is a different thing to reason about and buys
 * little — these five are the questions an advert is actually read for.
 *
 * <p>It also makes the answer worth caching and the cost predictable: five questions per
 * advert is a ceiling, and a free-text box has none against a budget sized for a nightly pass.
 *
 * <h2>Each one names what it wants, not how to say it</h2>
 *
 * <p>The text below goes into the prompt verbatim as the question. It is English because this
 * is repository language; the advert is German and so is the answer, which the instruction
 * settles rather than this enum.
 */
public enum AdvertQuestion {

    /** The number the newsletter never carries and the portal sometimes does. */
    RATE("rate", "What does this advert say about the rate, the budget, or how it is paid?"),

    /** Who the work is actually for, which an agency advert often keeps back. */
    CLIENT("client", "What does this advert say about the end client, its industry, or its size?"),

    /** The part that decides whether a remote engagement is really remote. */
    ONSITE("onsite", "What does this advert say about being on site: how often, where, and for what?"),

    /** What the first weeks look like, which decides whether a start date is real. */
    ONBOARDING("onboarding", "What does this advert say about onboarding, the start, or the first weeks?"),

    /** Whether the stated duration is the whole story. */
    EXTENSION("extension", "What does this advert say about extending beyond the stated duration?");

    private final String key;
    private final String text;

    AdvertQuestion(String key, String text) {
        this.key = key;
        this.text = text;
    }

    /** The name this question travels under, in a request and in the browser's catalog. */
    public String key() {
        return key;
    }

    /** The question as the prompt asks it. */
    public String text() {
        return text;
    }

    /**
     * The question named, or a 400 naming the ones that exist.
     *
     * <p>Never a default: a question the server silently replaced would answer about something
     * the reader did not ask, and the answer would look exactly as authoritative.
     */
    public static AdvertQuestion of(String name) {
        if (name == null || name.isBlank()) {
            throw new BadShortlistRequest("name a question; this advert answers %s".formatted(keys()));
        }
        return Arrays.stream(values())
                .filter(question -> question.key.equalsIgnoreCase(name.trim()))
                .findFirst()
                .orElseThrow(() -> new BadShortlistRequest(
                        "'%s' is not a question this advert answers; it answers %s".formatted(name, keys())));
    }

    private static String keys() {
        return Arrays.stream(values()).map(AdvertQuestion::key).collect(Collectors.joining(", "));
    }
}
