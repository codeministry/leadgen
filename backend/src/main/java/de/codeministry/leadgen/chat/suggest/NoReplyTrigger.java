/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.chat.suggest;

import de.codeministry.leadgen.application.ApplicationService;
import de.codeministry.leadgen.application.ApplicationStatus;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Applications sent at least {@code no_reply_days} ago and still at {@code SENT}, read from the
 * pipeline board ({@link ApplicationService#board()}). {@code REPLIED} and later are an answer.
 */
@Component
@RequiredArgsConstructor
class NoReplyTrigger implements SuggestionTrigger {

    private final ApplicationService applications;

    @Override
    public String key() {
        return SuggestionService.NO_REPLY;
    }

    @Override
    public Optional<SuggestionCandidate> evaluate(SuggestionScope scope, SuggestionThresholds thresholds) {
        LocalDate cutoff = LocalDate.now(ZoneId.systemDefault()).minusDays(thresholds.noReplyDays());
        int count = (int) applications.board().stream()
                .filter(application -> application.status() == ApplicationStatus.SENT)
                .filter(application ->
                        application.sentOn() != null && !application.sentOn().isAfter(cutoff))
                .count();
        return count == 0
                ? Optional.empty()
                : Optional.of(SuggestionCandidate.of(
                        key(), count, Map.of("days", Integer.toString(thresholds.noReplyDays()))));
    }
}
