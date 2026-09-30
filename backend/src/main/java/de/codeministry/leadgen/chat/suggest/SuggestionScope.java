/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.chat.suggest;

import de.codeministry.leadgen.chat.ChatContextItem;
import java.util.List;

/**
 * What the chat the suggestions are for already has pinned: the conversation's offer and the
 * context chips. The caller resolves both, from the conversation or from the request's
 * {@code ?context=}, so this package never depends on a repository that also writes.
 */
public record SuggestionScope(Long pinnedOfferId, List<ChatContextItem> context) {

    /** An empty chat with nothing pinned. */
    public static final SuggestionScope NONE = new SuggestionScope(null, List.of());

    public SuggestionScope {
        context = context == null ? List.of() : List.copyOf(context);
    }
}
