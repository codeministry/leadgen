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
 * A tool call, sent once when it starts and once when it ends, under the same ordinal.
 *
 * @param ordinal    position of the call within the turn, from 1.
 * @param tool       the tool's name, as the model called it.
 * @param label      what the call did, in words the screen shows — the arguments that matter,
 *                   never raw JSON.
 * @param state      running or done.
 * @param count      how many rows the call returned; null while it runs.
 * @param durationMs how long it took; null while it runs.
 */
public record ChatStep(int ordinal, String tool, String label, ChatStepState state, Integer count, Long durationMs)
        implements ChatEvent {

    @Override
    public String event() {
        return "step";
    }
}
