/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.answer;

import java.util.List;

/**
 * {@code blocks}: the kind of every block the model decided, ascending by index. Blocks a rule
 * or the label cache decided are not in it, because no model was asked about them.
 *
 * @param blocks one entry per index whose stored {@code by} is {@code MODEL}.
 */
public record BlocksAnswer(List<LabelledBlock> blocks) implements QuestionAnswer {}
