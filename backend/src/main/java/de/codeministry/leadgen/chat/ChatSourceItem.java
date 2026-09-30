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
 * One entry of a turn's sources: a row the answer cited ({@link ChatSource}) or the numbers a
 * {@code statistics} call returned ({@link StatisticsSource}). Each serialises with its own fields
 * and a {@code kind} that tells the screen which one it is.
 */
public sealed interface ChatSourceItem permits ChatSource, StatisticsSource {

    /** What the entry is; the field the screen switches on. */
    ChatSourceKind kind();
}
