/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.chat.tools;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.model.ToolContext;

/**
 * A conversation's pins as the tools read them (ISC-452, ISC-453): the offers, the shortlist views
 * as their query strings, and the first analytics window, each in the order it was pinned.
 *
 * <p>The turn hands it to every tool call as Spring AI's {@link ToolContext}, which is never part of
 * a tool's schema: the model cannot see it, name it or argue with it. That is the point — a pinned
 * view or window is the screen's, and it wins over whatever the model asks for.
 *
 * <p>Its own record in this package, rather than the conversation's {@code ChatContextItem}s, so the
 * tools do not depend on the chat package that depends on them.
 *
 * @param offers     the pinned offers' ids, at most ten
 * @param views      the pinned shortlist views, each a {@code GET /api/v1/offers} query string
 * @param windowFrom the first pinned analytics window's first day, or null
 * @param windowTo   its last day, or null
 */
public record PinnedContext(List<Long> offers, List<String> views, LocalDate windowFrom, LocalDate windowTo) {

    /** The key the context travels under in the tool context's map. */
    public static final String KEY = "leadgen.pins";

    /** No pins at all. */
    public static final PinnedContext NONE = new PinnedContext(List.of(), List.of(), null, null);

    public PinnedContext {
        offers = List.copyOf(offers);
        views = List.copyOf(views);
    }

    /**
     * The tool context for one call. Never empty, not even without pins: Spring AI refuses to call a
     * method that takes a {@link ToolContext} with an empty one.
     */
    public ToolContext toolContext() {
        return new ToolContext(Map.of(KEY, this));
    }

    /** The pins a call was handed; {@link #NONE} when it was called without any (a test, a direct call). */
    public static PinnedContext of(ToolContext context) {
        return context != null && context.getContext().get(KEY) instanceof PinnedContext pins ? pins : NONE;
    }

    /** Whether an analytics window is pinned. */
    public boolean hasWindow() {
        return windowFrom != null && windowTo != null;
    }
}
