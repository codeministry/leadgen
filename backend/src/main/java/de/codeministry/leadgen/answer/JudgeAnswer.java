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
 * {@code judge}: the points given to each factor the model is asked about, in
 * {@code Judge.JUDGED} order. A factor with no row reads 0, which is what an absent penalty
 * means.
 *
 * @param reasons one entry per judged factor.
 */
public record JudgeAnswer(List<JudgedPoints> reasons) implements QuestionAnswer {}
