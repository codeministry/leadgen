/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.chat.catalog;

/**
 * One model a connection offers the chat, with what a call on it costs (spec 026, ISC-517).
 *
 * <p>The two cost components name three states: {@code free} on an unbilled connection, with no
 * price; a {@code price} on a billed connection whose model the operator priced in
 * {@code chat.prices}; and neither on a billed one the table does not name, which the picker shows
 * as "price unknown" and never as free.
 *
 * @param name         the model's name as the connection reported it.
 * @param defaultModel whether it is the model a turn without a pick takes: {@code llm.models.chat},
 *                     else {@code scoring}, on the primary connection.
 * @param free         whether a call on it costs nothing because its connection is not billed.
 * @param price        what it costs in USD per million tokens, or {@code null} when it is free or
 *                     unpriced.
 */
public record ModelView(String name, boolean defaultModel, boolean free, ModelPrice price) {

    /** The model as listed, before {@link ChatModelCatalog} joins the price table onto it. */
    static ModelView listed(String name, boolean defaultModel) {
        return new ModelView(name, defaultModel, false, null);
    }

    /** This model on an unbilled connection, where every call is free whatever the table says. */
    ModelView asFree() {
        return new ModelView(name, defaultModel, true, null);
    }

    /** This model on a billed connection, at {@code price}, or unpriced when that is {@code null}. */
    ModelView billedAt(ModelPrice price) {
        return new ModelView(name, defaultModel, false, price);
    }
}
