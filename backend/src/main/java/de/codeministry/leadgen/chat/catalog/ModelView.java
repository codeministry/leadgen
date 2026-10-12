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
 * One model a connection offers the chat.
 *
 * @param name         the model's name as the connection reported it.
 * @param defaultModel whether it is the model a turn without a pick takes: {@code llm.models.chat},
 *                     else {@code scoring}, on the primary connection.
 */
public record ModelView(String name, boolean defaultModel) {}
