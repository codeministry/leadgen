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
import java.time.Instant;
import java.util.List;

/**
 * One stored turn, as a reload shows it.
 *
 * @param id             the turn.
 * @param question       what was asked.
 * @param answer         the answer as it was streamed, citations resolved as in {@link ChatText}.
 * @param state          where it stands.
 * @param steps          the tool calls it made, done.
 * @param sources        the rows it cited, then its statistics calls, as {@link ChatSources} sent them.
 * @param replacesTurnId the turn a regenerate replaced, or null.
 * @param model          which model answered.
 * @param connection     the connection it answered on, {@code PRIMARY} or {@code SECOND}; null for a
 *                       turn stored before spec 025 (ISC-507).
 * @param createdAt      when it started.
 * @param endReason      why it ended incomplete, as its live {@code error} event said; null for every
 *                       other ending and for a turn stored before the reason was (spec 022, ISC-473).
 * @param finishedAt     when it ended, or null while it streams.
 * @param inputTokens    the input tokens the provider reported over every model call of the turn
 *                       (spec 026, ISC-518); left out of the JSON when null, which is a turn stored
 *                       before V40 or one no call reported a count for — no data, never zero. A billed
 *                       turn whose calls reported nothing at all carries 0 here and in
 *                       {@code outputTokens} with no {@code costUsd}: usage not reported, not priced.
 * @param outputTokens   the output tokens, likewise.
 * @param costUsd        the turn's cost in USD as a decimal string, exact as stored; left out of the
 *                       JSON when null.
 * @param usageIncomplete true when a billed turn's usage was not fully reported — a model
 *                       round delivered part of its stream and reported no usage — so its counts are a
 *                       lower bound and it has no cost; left out otherwise, which is also what an unpriced
 *                       billed turn carries (spec 026).
 */
public record TurnView(
        long id,
        String question,
        String answer,
        ChatTurnState state,
        List<ChatStep> steps,
        List<ChatSourceItem> sources,
        Long replacesTurnId,
        String model,
        String connection,
        Instant createdAt,
        ChatErrorReason endReason,
        Instant finishedAt,
        @JsonInclude(JsonInclude.Include.NON_NULL) Integer inputTokens,
        @JsonInclude(JsonInclude.Include.NON_NULL) Integer outputTokens,
        @JsonInclude(JsonInclude.Include.NON_NULL) String costUsd,
        @JsonInclude(JsonInclude.Include.NON_NULL) Boolean usageIncomplete) {

    /** A turn whose connection and usage are not read yet. */
    public TurnView(
            long id,
            String question,
            String answer,
            ChatTurnState state,
            List<ChatStep> steps,
            List<ChatSourceItem> sources,
            Long replacesTurnId,
            String model,
            Instant createdAt,
            ChatErrorReason endReason,
            Instant finishedAt) {
        this(
                id,
                question,
                answer,
                state,
                steps,
                sources,
                replacesTurnId,
                model,
                null,
                createdAt,
                endReason,
                finishedAt,
                null,
                null,
                null,
                null);
    }
}
