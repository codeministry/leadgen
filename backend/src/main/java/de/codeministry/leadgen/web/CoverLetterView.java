/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.web;

import de.codeministry.leadgen.packaging.CoverLetter;
import java.time.Instant;

/**
 * The cover letter of one offer's package, as the offer detail view shows it.
 *
 * @param text   the letter, byte for byte what `cover_letter.txt` in the package holds
 * @param author `model`, `template` or `edited`
 * @param at     when the text was last written, by a build or a save
 * @param frozen whether the application has ever been sent, so neither a save nor a redraft is
 *               accepted; the server's one reading of "sent", so the page does not guess
 */
public record CoverLetterView(String text, String author, Instant at, boolean frozen) {

    static CoverLetterView of(CoverLetter letter) {
        return new CoverLetterView(letter.text(), letter.author(), letter.at(), letter.frozen());
    }
}
