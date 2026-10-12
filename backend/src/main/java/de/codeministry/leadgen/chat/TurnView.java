/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.chat;

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
        Instant finishedAt) {

    /** A turn whose connection is not read yet. */
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
                finishedAt);
    }
}
