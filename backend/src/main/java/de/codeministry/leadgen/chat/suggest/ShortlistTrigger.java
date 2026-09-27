/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.chat.suggest;

import de.codeministry.leadgen.offer.OfferQueryService;
import de.codeministry.leadgen.offer.RelatedFilter;
import de.codeministry.leadgen.offer.ScoreFilter;
import de.codeministry.leadgen.offer.ScoreState;
import de.codeministry.leadgen.offer.ShortlistQuery;
import de.codeministry.leadgen.offer.ShortlistSort;
import de.codeministry.leadgen.offer.StartWindow;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Evergreen: what stands on the shortlist, as the shortlist's own match count for its
 * {@code shortlist} band. Always one candidate, zero included.
 */
@Component
@RequiredArgsConstructor
class ShortlistTrigger implements SuggestionTrigger {

    private final OfferQueryService offers;

    @Override
    public String key() {
        return SuggestionService.SHORTLIST;
    }

    @Override
    public Optional<SuggestionCandidate> evaluate(SuggestionScope scope, SuggestionThresholds thresholds) {
        ShortlistQuery query = new ShortlistQuery(
                null,
                new ScoreFilter("shortlist", null, null, ScoreState.ANY),
                List.of(),
                false,
                ShortlistSort.SCORE,
                StartWindow.of(null),
                RelatedFilter.ANY,
                null,
                false,
                false,
                null,
                null,
                1);
        int matched = offers.shortlist(query, () -> false).matched();
        return Optional.of(SuggestionCandidate.of(key(), matched, Map.of()));
    }
}
