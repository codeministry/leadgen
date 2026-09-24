/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.web;

import jakarta.validation.constraints.NotBlank;

/**
 * A person's version of the letter. Stored as sent, whitespace and all: the text is what the
 * package carries, and trimming it here would make the download differ from what was typed.
 * Blank is refused, because an empty letter saved by accident would be kept by every rebuild.
 */
public record CoverLetterEdit(@NotBlank String text) {}
