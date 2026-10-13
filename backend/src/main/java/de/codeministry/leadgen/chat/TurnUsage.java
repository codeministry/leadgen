/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.chat;

import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;

/**
 * Tokens the provider reported for a turn's model calls (spec 026, ISC-518): input and output, each
 * null while no call has reported one. Null is "no data" — a provider that sends no usage, a turn
 * stored before V40 — and never reads as zero.
 *
 * @param inputTokens  the prompt tokens, summed over the calls that reported them; null if none did.
 * @param outputTokens the completion tokens, summed likewise; null if none did.
 */
public record TurnUsage(Integer inputTokens, Integer outputTokens) {

    /** Nothing reported. */
    public static final TurnUsage NONE = new TurnUsage(null, null);

    /**
     * Zero of each: what a billed turn stores when a call delivered part of its stream and none
     * reported a count, so the spend lists it as unreported instead of leaving it out.
     */
    public static final TurnUsage ZERO = new TurnUsage(0, 0);

    /** Whether any count is known. */
    public boolean reported() {
        return inputTokens != null || outputTokens != null;
    }

    /** Both counts added, a side that is null counting as absent rather than as zero. */
    public TurnUsage plus(TurnUsage other) {
        return new TurnUsage(sum(inputTokens, other.inputTokens), sum(outputTokens, other.outputTokens));
    }

    /**
     * The usage one streamed chunk reports, or {@link #NONE} when it reports none.
     *
     * <p>Both chat kinds carry a running or final figure for the whole call, never a delta: the
     * OpenAI module on a chunk of its own after the last choice (asked for with
     * {@code stream_options.include_usage}), the Messages API on its {@code message_delta}, with the
     * input tokens of {@code message_start} already folded in. Every other chunk reports nothing —
     * Spring AI fills it with an empty usage or with zeros — so a chunk counts only when it names a
     * token, and the last such chunk of a call is that call's count.
     */
    public static TurnUsage of(ChatResponse response) {
        if (response == null || response.getMetadata() == null) {
            return NONE;
        }
        Usage usage = response.getMetadata().getUsage();
        if (usage == null) {
            return NONE;
        }
        int input = count(usage.getPromptTokens());
        int output = count(usage.getCompletionTokens());
        // A chunk that names a token reports both halves; a zero beside it is a real zero.
        return input == 0 && output == 0 ? NONE : new TurnUsage(input, output);
    }

    private static int count(Integer tokens) {
        return tokens == null || tokens < 0 ? 0 : tokens;
    }

    private static Integer sum(Integer a, Integer b) {
        if (a == null) {
            return b;
        }
        return b == null ? a : Integer.valueOf(a + b);
    }
}
