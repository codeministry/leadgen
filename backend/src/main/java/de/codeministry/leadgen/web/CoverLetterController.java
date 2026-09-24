/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.web;

import de.codeministry.leadgen.packaging.CoverLetterService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/**
 * The cover letter of an offer's package: read it, save a person's version of it, or ask the
 * writing model for a fresh draft.
 *
 * <p>Nothing here sends anything. Saving writes the file the package download serves and the
 * copy on the offer row; there is no recipient and no channel, which is what
 * {@code NothingIsSentTest} reads the repository for.
 *
 * <p><b>A letter that went out is frozen.</b> Once the application has been sent, the letter is
 * the record of what the client received, so both writes answer 409 rather than changing it.
 *
 * <p>The draft is synchronous, like {@code POST /offers/{id}/fetch}: one model call for one
 * offer, and a spent call budget is a 429 with nothing written rather than a wait.
 */
@RestController
@RequestMapping("/api/v1/offers")
class CoverLetterController {

    private final CoverLetterService letters;

    CoverLetterController(CoverLetterService letters) {
        this.letters = letters;
    }

    @GetMapping("/{id}/cover-letter")
    CoverLetterView read(@PathVariable long id) {
        return CoverLetterView.of(letters.read(id));
    }

    @PutMapping("/{id}/cover-letter")
    CoverLetterView save(@PathVariable long id, @Valid @RequestBody CoverLetterEdit edit) {
        return CoverLetterView.of(letters.save(id, edit.text()));
    }

    @PostMapping("/{id}/cover-letter/draft")
    CoverLetterView draft(@PathVariable long id) {
        return CoverLetterView.of(letters.draft(id));
    }

    /** No package, so no letter; the same sentence convention as the package download's 404. */
    @ExceptionHandler(CoverLetterService.NoLetter.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    String noLetter(CoverLetterService.NoLetter e) {
        return e.getMessage();
    }

    /** The application was sent; the letter is what the client received and stays that. */
    @ExceptionHandler(CoverLetterService.AlreadySent.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    String alreadySent(CoverLetterService.AlreadySent e) {
        return e.getMessage();
    }

    /** No model call left in today's budget; nothing was written. */
    @ExceptionHandler(CoverLetterService.NoPermit.class)
    @ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
    String noPermit(CoverLetterService.NoPermit e) {
        return e.getMessage();
    }
}
