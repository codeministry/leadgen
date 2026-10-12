/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.chat;

/**
 * Which model a turn is asked of, as the drawer's select sent it (spec 025, ISC-507).
 *
 * <p>Both halves or neither: a pick names a connection, {@code PRIMARY} or {@code SECOND}, and a
 * model that connection listed in {@code GET /api/v1/chat/models}. Neither means the default, the
 * chat model on the primary connection, which is what every turn asked before this record existed.
 *
 * @param connection the connection's name, or null for the default.
 * @param model      the model's name as the connection reported it, or null for the default.
 */
public record ModelPick(String connection, String model) {

    /** The default: no pick, the chat model on the primary connection. */
    public static final ModelPick DEFAULT = new ModelPick(null, null);

    /** Whether this pick leaves the choice to the default. */
    public boolean isDefault() {
        return blank(connection) && blank(model);
    }

    /** Whether exactly one half was sent, which no select produces. */
    public boolean isPartial() {
        return blank(connection) != blank(model);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
