/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.answer;

/**
 * One question's answer in the shape both sides share: the candidate's parsed reply and the
 * stored answer are the same type, so the script compares like with like.
 */
public sealed interface QuestionAnswer permits BlocksAnswer, FieldsAnswer, JudgeAnswer {}
