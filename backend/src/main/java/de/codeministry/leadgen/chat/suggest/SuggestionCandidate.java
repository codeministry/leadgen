/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.chat.suggest;

import java.util.Map;

/**
 * One question the data supports, before anyone phrases it.
 *
 * @param trigger     the trigger's key, one of the constants on {@link SuggestionService}
 * @param count       the number the trigger found, as the screen behind it would show it
 * @param sentenceKey the catalog's own sentence for this trigger, {@code chat.suggest.*}
 * @param params      what the sentence interpolates besides {@code count}; never null
 */
public record SuggestionCandidate(String trigger, int count, String sentenceKey, Map<String, String> params) {

    public SuggestionCandidate {
        params = params == null ? Map.of() : Map.copyOf(params);
    }

    static SuggestionCandidate of(String trigger, int count, Map<String, String> params) {
        return new SuggestionCandidate(trigger, count, "chat.suggest." + trigger, params);
    }
}
