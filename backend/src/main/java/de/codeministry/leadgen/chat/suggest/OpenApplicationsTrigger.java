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
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Evergreen: the applications on the pipeline board that are not closed
 * ({@link de.codeministry.leadgen.application.ApplicationStatus#isClosed()}). Always one
 * candidate, zero included.
 */
@Component
@RequiredArgsConstructor
class OpenApplicationsTrigger implements SuggestionTrigger {

    private final ApplicationService applications;

    @Override
    public String key() {
        return SuggestionService.OPEN_APPLICATIONS;
    }

    @Override
    public Optional<SuggestionCandidate> evaluate(SuggestionScope scope, SuggestionThresholds thresholds) {
        int open = (int) applications.board().stream()
                .filter(application -> !application.status().isClosed())
                .count();
        return Optional.of(SuggestionCandidate.of(key(), open, Map.of()));
    }
}
