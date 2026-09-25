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
 * {@code fields}: start, duration and deadline as the field extractor reads them.
 *
 * @param start    {@code start_text} and {@code starts_on}.
 * @param duration {@code duration} and {@code duration_months}.
 * @param deadline {@code apply_by_text} and {@code apply_by}.
 */
public record FieldsAnswer(DatedField start, DurationField duration, DatedField deadline) implements QuestionAnswer {}
