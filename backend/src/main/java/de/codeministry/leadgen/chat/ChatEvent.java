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
 * One server-sent event of a turn. The event name on the wire is {@link #event()}; the data is
 * the record itself, as JSON.
 *
 * <p>The order a turn emits them in is fixed: {@link ChatTurnStarted} once and first, then any
 * number of {@link ChatStep} and {@link ChatText} interleaved, then either {@link ChatSources}
 * followed by {@link ChatDone}, or {@link ChatError} — preceded by {@link ChatSources} when the
 * partial answer cited anything, so its pills have their rows, and alone otherwise. At most one
 * {@link ChatSources}, always right before the terminal event. A stream that ends any other way
 * was cut, and the browser treats it as an error.
 */
public sealed interface ChatEvent permits ChatTurnStarted, ChatStep, ChatText, ChatSources, ChatError, ChatDone {

    /** The SSE event name. */
    String event();
}
