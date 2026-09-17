/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */

/**
 * Asking one advert a bounded question, on the detail screen.
 *
 * <p>Not a pipeline stage and deliberately not one: nothing here runs at night, writes a
 * column or reports a count. It answers a click, and the answer lives as long as the screen.
 *
 * <p><b>It needs no retrieval, and that is the point.</b> One de-furnitured advert fits whole
 * in any context window worth configuring, so there is nothing to choose between — retrieval
 * exists for the case where everything will not fit. {@code docs/decisions/retrieval.md}
 * refuses corpus-wide question answering and names this as the carve-out worth having: the
 * same question asked at a granularity where the answer can be checked.
 *
 * <p><b>Every claim carries a sentence from the advert, and one that is not in the advert is
 * dropped.</b> That is the whole design, and the rest of the package is arrangements around
 * it. Without the check this screen would be a fluent paragraph about a document the reader is
 * looking at, which is the most expensive kind of wrong: plausible, specific, and
 * unfalsifiable at a glance. With it, a wrong answer is a missing answer.
 *
 * <p>The questions are an enum rather than a text box, so nothing a caller sends reaches a
 * prompt and the cost per advert has a ceiling. The model is {@code llm.models.scoring}, the
 * one three stages already share.
 */
package de.codeministry.leadgen.ask;
