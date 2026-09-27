/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.chat.tools;

import de.codeministry.leadgen.application.ApplicationEvent;
import de.codeministry.leadgen.application.ApplicationStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * One application as the application tool hands it to the model: where it stands, how it got
 * there, and the letter that went with it.
 *
 * <p>Deliberately not {@code ApplicationView}: that carries the package folder's path, the
 * advert's URL and the agency, which are the screen's links and a filesystem location, not
 * answers. The letter is included whole because "what did I write to them" is the question.
 *
 * @param applicationId      the application's own id
 * @param offerId            the offer it is for, which is what an answer cites
 * @param title              the advert's title
 * @param status             where it stands now
 * @param score              the offer's score, or null while unjudged
 * @param rateEur            the rate recorded for it, or null
 * @param sentOn             the day it was sent, or null
 * @param followUpOn         the follow-up day, or null
 * @param followUpDue        whether that day has come
 * @param outcome            the outcome recorded by hand, or null
 * @param note               the operator's note, or null
 * @param events             every status change, newest first, as the screen lists them
 * @param coverLetter        the letter's text, or null when none was written
 * @param coverLetterAt      when the letter was last written or edited, or null
 * @param coverLetterFrozen  whether the letter is the record of what was sent and can no longer change
 */
public record ApplicationResult(
        long applicationId,
        long offerId,
        String title,
        ApplicationStatus status,
        Integer score,
        BigDecimal rateEur,
        LocalDate sentOn,
        LocalDate followUpOn,
        boolean followUpDue,
        String outcome,
        String note,
        List<ApplicationEvent> events,
        String coverLetter,
        Instant coverLetterAt,
        boolean coverLetterFrozen) {}
