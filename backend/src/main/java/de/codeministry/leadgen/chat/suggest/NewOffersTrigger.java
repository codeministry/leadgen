/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.chat.suggest;

import de.codeministry.leadgen.analytics.LastRunQueryService;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The offers the last run wrote, read from the dashboard's last-run card
 * ({@link LastRunQueryService#lastRun()}), when there are at least {@code new_offers_min}.
 */
@Component
@RequiredArgsConstructor
class NewOffersTrigger implements SuggestionTrigger {

    private final LastRunQueryService lastRun;

    @Override
    public String key() {
        return SuggestionService.NEW_OFFERS;
    }

    @Override
    public Optional<SuggestionCandidate> evaluate(SuggestionScope scope, SuggestionThresholds thresholds) {
        return lastRun.lastRun()
                .filter(run -> run.written() >= thresholds.newOffersMin())
                .map(run -> SuggestionCandidate.of(
                        key(), run.written(), Map.of("shortlisted", Integer.toString(run.shortlisted()))));
    }
}
