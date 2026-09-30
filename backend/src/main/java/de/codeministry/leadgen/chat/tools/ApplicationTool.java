/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.chat.tools;

import de.codeministry.leadgen.application.ApplicationService;
import de.codeministry.leadgen.packaging.CoverLetter;
import de.codeministry.leadgen.packaging.CoverLetterService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * One application, as a tool the chat model can call.
 *
 * <p>Looked up by the <b>offer's</b> id, because that is the id every other tool returns and
 * every answer cites; an application id would be a second numbering the model would have to be
 * told apart from the first. The state and the event log come from {@link ApplicationService},
 * the letter from {@link CoverLetterService#read}, the same reads the pipeline screen and {@code
 * GET /offers/{id}/cover-letter} make, so the letter is the file the package download serves.
 *
 * <p>It never returns the package folder's path, the advert's URL or text, the agency, or
 * anything read from a mail; {@link ApplicationResult} is the whole shape. It changes nothing:
 * moving an application is a person's click, never a tool call.
 */
@Component
@RequiredArgsConstructor
public class ApplicationTool {

    private final ApplicationService applications;
    private final CoverLetterService letters;

    @Tool(
            name = "application",
            description = "Returns the application for one offer: its status, every status change newest"
                    + " first, dates, the note, and the cover letter's text if one was written. Look it up"
                    + " by the offer's id.")
    public ApplicationResult application(
            @ToolParam(description = "The offer's id, as the other tools return it.") long offerId) {
        // By the offer, archived included: the board leaves archived offers out, and an archived
        // offer's application — sent, answered, done with — is exactly the one asked about later.
        var view = applications
                .findByOffer(offerId)
                .orElseThrow(() -> new IllegalArgumentException("offer " + offerId + " has no application"));
        CoverLetter letter = letterOf(offerId);
        return new ApplicationResult(
                view.id(),
                view.offerId(),
                view.title(),
                view.status(),
                view.scoreValue(),
                view.rateEur(),
                view.sentOn(),
                view.followUpOn(),
                view.followUpDue(),
                view.outcome(),
                view.note(),
                applications.history(view.id()),
                letter == null ? null : letter.text(),
                letter == null ? null : letter.at(),
                letter != null && letter.frozen());
    }

    /** Null when no letter exists yet, which is a state of the application and not an error. */
    private CoverLetter letterOf(long offerId) {
        try {
            return letters.read(offerId);
        } catch (CoverLetterService.NoLetter e) {
            return null;
        }
    }
}
