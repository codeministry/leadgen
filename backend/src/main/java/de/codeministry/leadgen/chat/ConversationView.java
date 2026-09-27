/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.chat;

import java.time.Instant;
import java.util.List;

/**
 * A whole conversation, as the drawer opens it.
 *
 * @param id            the conversation.
 * @param title         the name it was renamed to, else the first question, shortened.
 * @param pinnedOfferId the first offer of {@code context}, or null; kept for one release, the context list
 *                      replaces it.
 * @param context       what the conversation is about, in the order its chips are shown; empty for
 *                      everything.
 * @param turns         oldest first.
 * @param updatedAt     when the last turn started.
 */
public record ConversationView(
        long id,
        String title,
        Long pinnedOfferId,
        List<ChatContextItem> context,
        List<TurnView> turns,
        Instant updatedAt) {}
