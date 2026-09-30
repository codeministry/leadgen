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
 * Last event of a turn that ended normally or was stopped.
 *
 * @param state {@link ChatTurnState#DONE} or {@link ChatTurnState#STOPPED}.
 */
public record ChatDone(ChatTurnState state) implements ChatEvent {

    @Override
    public String event() {
        return "done";
    }
}
