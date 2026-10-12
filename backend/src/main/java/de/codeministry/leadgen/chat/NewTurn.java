/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.chat;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The body of {@code POST /api/v1/chat/conversations/{id}/turns}.
 *
 * @param question   what the operator typed.
 * @param connection the connection the select picked, or null for the default (spec 025, ISC-507).
 * @param model      the model the select picked, or null for the default.
 */
public record NewTurn(@NotBlank @Size(max = 4000) String question, String connection, String model) {

    /** A question with no pick, as every turn was asked before spec 025. */
    public NewTurn(String question) {
        this(question, null, null);
    }

    /** The pick this turn carries. */
    public ModelPick pick() {
        return new ModelPick(connection, model);
    }
}
