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
 * The optional body of {@code POST …/turns/{turnId}/regenerate}: ask the question again, on the
 * same default or on another model (spec 025, ISC-508). An absent or empty body is the default.
 *
 * @param connection the connection the select picked, or null for the default.
 * @param model      the model the select picked, or null for the default.
 */
public record RegenerateTurn(String connection, String model) {

    /** The pick this regenerate carries. */
    public ModelPick pick() {
        return new ModelPick(connection, model);
    }
}
