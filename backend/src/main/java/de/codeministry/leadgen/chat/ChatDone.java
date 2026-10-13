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
 * Last event of a turn that ended normally or was stopped.
 *
 * @param state        {@link ChatTurnState#DONE} or {@link ChatTurnState#STOPPED}.
 * @param inputTokens  the input tokens the provider reported over every model call of the turn, as
 *                     the row stores them (spec 026, ISC-518); left out when no call reported any on
 *                     an unbilled connection, 0 beside an output of 0 and no cost on a billed one.
 * @param outputTokens the output tokens, likewise.
 * @param costUsd      the turn's cost in USD as a decimal string; left out while it has none.
 * @param usageIncomplete true when a billed turn's usage was not fully reported — a model
 *                     round delivered part of its stream and reported no usage — so its counts are a
 *                     lower bound and it has no cost; left out otherwise, which is also what an unpriced
 *                     billed turn carries (spec 026).
 */
public record ChatDone(
        ChatTurnState state,
        @JsonInclude(JsonInclude.Include.NON_NULL) Integer inputTokens,
        @JsonInclude(JsonInclude.Include.NON_NULL) Integer outputTokens,
        @JsonInclude(JsonInclude.Include.NON_NULL) String costUsd,
        @JsonInclude(JsonInclude.Include.NON_NULL) Boolean usageIncomplete)
        implements ChatEvent {

    /** A turn's end without any usage reported. */
    public ChatDone(ChatTurnState state) {
        this(state, null, null, null, null);
    }

    @Override
    public String event() {
        return "done";
    }
}
