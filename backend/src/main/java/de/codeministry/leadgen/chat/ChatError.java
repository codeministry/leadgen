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
 * Ends a turn that could not finish; the partial answer is kept and the turn marked incomplete.
 *
 * @param reason  which of the three ways it ended.
 * @param message one sentence for the screen, in English; the catalog key is derived from
 *                {@code reason}, so this is for the log and the fallback.
 */
public record ChatError(ChatErrorReason reason, String message) implements ChatEvent {

    @Override
    public String event() {
        return "error";
    }
}
