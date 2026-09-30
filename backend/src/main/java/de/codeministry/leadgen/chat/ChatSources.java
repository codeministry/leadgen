/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.chat;

import java.util.List;

/**
 * The rows the answer cited, sent exactly once, after the last text and before {@link ChatDone}.
 *
 * @param sources the cited rows in citation order, then one entry per {@code statistics} call in
 *                call order; empty when the answer cited nothing and asked for no numbers.
 */
public record ChatSources(List<ChatSourceItem> sources) implements ChatEvent {

    @Override
    public String event() {
        return "sources";
    }
}
