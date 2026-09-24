/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.packaging;

import java.time.Instant;

/**
 * One package's cover letter and who wrote it.
 *
 * @param text   what `cover_letter.txt` holds
 * @param author `model`, `template` or `edited`
 * @param at     when the text was last written; for a package built before the letter was
 *               stored, when the package was built
 */
public record CoverLetter(String text, String author, Instant at) {}
