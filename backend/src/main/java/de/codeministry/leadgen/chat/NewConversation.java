/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.chat;

import java.util.List;

/**
 * The body of {@code POST /api/v1/chat/conversations}.
 *
 * @param pinnedOfferId the offer to pin, or null; still read for one release, the context list
 *                      replaces it.
 * @param context       what the conversation is about, or null for everything.
 */
public record NewConversation(Long pinnedOfferId, List<ChatContextItem> context) {

    /**
     * The offer the conversation is pinned to: {@link #pinnedOfferId} when it is given, the first
     * offer of the context list otherwise, null when neither names one.
     */
    public Long pin() {
        if (pinnedOfferId != null || context == null) {
            return pinnedOfferId;
        }
        return context.stream()
                .filter(item -> item != null && item.kind() == ChatContextKind.OFFER && item.offerId() != null)
                .map(ChatContextItem::offerId)
                .findFirst()
                .orElse(null);
    }
}
