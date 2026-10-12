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
 * First event of every turn: the id the stop and regenerate calls address.
 *
 * @param turnId the stored turn, already written with state {@code STREAMING}.
 * @param model the model answering it, as {@code chat_turn.model} stores it, so the live turn's
 *     status names it before a reload does; null where none is configured.
 * @param connection the connection it answers on, {@code PRIMARY} or {@code SECOND} (spec 025,
 *     ISC-507); null where no model is configured.
 */
public record ChatTurnStarted(long turnId, String model, String connection) implements ChatEvent {

    /** A turn started before its connection is known. */
    public ChatTurnStarted(long turnId, String model) {
        this(turnId, model, null);
    }

    @Override
    public String event() {
        return "turn";
    }
}
