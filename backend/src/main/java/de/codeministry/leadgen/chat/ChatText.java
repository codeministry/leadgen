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
 * A piece of the answer, as Markdown.
 *
 * <p><b>Citations arrive already resolved.</b> A citation the turn's ledger holds is a link
 * {@code [n](cite:offer/ID)} or {@code [n](cite:application/ID)}, numbered like its entry in
 * {@link ChatSources}; one it does not hold is {@code ⟨unverified:ID⟩} and never a link. The
 * browser renders what it is given and decides nothing about grounding.
 *
 * @param delta the next piece of text; never a split marker.
 */
public record ChatText(String delta) implements ChatEvent {

    @Override
    public String event() {
        return "text";
    }
}
