/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.chat;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Ends a turn that could not finish; the partial answer is kept and the turn marked incomplete.
 *
 * @param reason       which of the three ways it ended.
 * @param message      one sentence for the screen, in English; the catalog key is derived from
 *                     {@code reason}, so this is for the log and the fallback.
 * @param inputTokens  the input tokens the row stores for the turn, as on {@link ChatDone} (spec 026,
 *                     ISC-518); left out when no call reported any, and 0 beside {@code outputTokens}
 *                     0 and no cost when a billed turn's calls reported nothing at all.
 * @param outputTokens the output tokens, likewise.
 * @param costUsd      the turn's cost in USD as a decimal string; left out while it has none.
 * @param usageIncomplete true when a billed turn's usage was not fully reported — a model
 *                     round delivered part of its stream and reported no usage — so its counts are a
 *                     lower bound and it has no cost; left out otherwise, which is also what an unpriced
 *                     billed turn carries (spec 026).
 */
public record ChatError(
        ChatErrorReason reason,
        String message,
        @JsonInclude(JsonInclude.Include.NON_NULL) Integer inputTokens,
        @JsonInclude(JsonInclude.Include.NON_NULL) Integer outputTokens,
        @JsonInclude(JsonInclude.Include.NON_NULL) String costUsd,
        @JsonInclude(JsonInclude.Include.NON_NULL) Boolean usageIncomplete)
        implements ChatEvent {

    /** An ending that carries no usage: the turn never started, or its row could not be written. */
    public ChatError(ChatErrorReason reason, String message) {
        this(reason, message, null, null, null, null);
    }

    @Override
    public String event() {
        return "error";
    }
}
