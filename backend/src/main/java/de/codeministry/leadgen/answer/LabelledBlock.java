/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.answer;

import de.codeministry.leadgen.content.ContentKind;

/**
 * One block's decided kind.
 *
 * @param index the block's position in {@code content_blocks}.
 * @param kind  the decided kind; {@code CONTENT} when the model omitted the index or answered
 *              a kind that does not exist.
 */
public record LabelledBlock(int index, ContentKind kind) {}
