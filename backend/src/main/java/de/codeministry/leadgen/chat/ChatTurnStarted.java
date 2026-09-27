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
 */
public record ChatTurnStarted(long turnId) implements ChatEvent {

    @Override
    public String event() {
        return "turn";
    }
}
