/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */

/**
 * The records the four YAML files bind to, and nothing else.
 *
 * <p>They mirror the nesting of their file rather than flattening it, which is the one place
 * this repository nests records inside a type: the structure <em>is</em> the documentation of
 * the file, and flattening it would lose exactly what it exists to describe.
 *
 * <p>Validation lives on the fields, because the answer to "is empty acceptable here" is a
 * question about the field: an unset language-model key is fine, an unset IMAP host on an
 * <em>enabled</em> source is not. {@code @Valid} goes on the type argument
 * ({@code List<@Valid Skill>}) and never on the container, or Hibernate Validator 9 logs an
 * {@code HV000271} per component at every start.
 *
 * <p>Two settings are rejected at load rather than ignored, and both for the same reason: a
 * key that is read, ignored and quietly does something else is the worst failure available.
 * {@code rate.apply_after} must be {@code enrichment}, because the sources state a rate in
 * 0.0 % of offers. {@code security.auth} must be {@code none}, because it is the only mode
 * implemented.
 */
package de.codeministry.leadgen.config.model;
