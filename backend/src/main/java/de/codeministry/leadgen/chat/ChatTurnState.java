/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.chat;

/** Where a stored turn stands. */
public enum ChatTurnState {
    /** The model is still writing. A turn left in this state by a crash reads as incomplete. */
    STREAMING,
    DONE,
    /** Ended by {@link ChatError}; the partial answer is kept. */
    INCOMPLETE,
    /** Ended by the operator's stop; the partial answer is kept. */
    STOPPED
}
