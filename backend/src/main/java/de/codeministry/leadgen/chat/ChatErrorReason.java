/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.chat;

/** Why a turn ended without an answer the model finished. */
public enum ChatErrorReason {
    /** The model failed or the connection to it broke. */
    MODEL,
    /** {@code chat.max_calls_per_day} refused the next call. */
    BUDGET,
    /** The turn spent {@code chat.max_tool_rounds}. */
    ROUNDS
}
