/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.chat.catalog;

import java.util.List;

/**
 * {@code GET /api/v1/chat/models}: what the chat's model select offers, read from each configured
 * connection rather than from a list in {@code .env} (spec 025, ISC-505). A turn may name only a
 * model this answer listed (ISC-506).
 *
 * @param connections      the primary connection first, then the second one when it is configured.
 * @param defaultModelName the name of the model a turn without a pick takes — {@code llm.models.chat},
 *                         else {@code scoring}, on the primary connection — verbatim as configured,
 *                         whether or not the primary listed it under that exact name; {@code null}
 *                         when no chat model is configured. Not {@link ModelView#defaultModel}, which
 *                         flags one listed model.
 * @param defaultModelPrice what that default costs on the primary, by the lookup a turn on it is costed
 *                         by ({@link ChatPrices#of}); {@code null} when the primary is not billed, when
 *                         nobody priced the model, or when there is no default. Decimal strings, as
 *                         every {@link ModelPrice}.
 */
public record ChatModelsView(List<ConnectionView> connections, String defaultModelName, ModelPrice defaultModelPrice) {

    /** The list before prices are joined on: no default price yet. */
    public ChatModelsView(List<ConnectionView> connections, String defaultModelName) {
        this(connections, defaultModelName, null);
    }
}
