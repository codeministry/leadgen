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
import de.codeministry.leadgen.offer.ShortlistEntry;
import de.codeministry.leadgen.offer.ShortlistQuery;
import de.codeministry.leadgen.offer.ShortlistSort;
import de.codeministry.leadgen.offer.StartWindow;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Open deadlines within {@code deadline_days}, read through the shortlist's own query: its
 * working set, "deadline still open", sorted by deadline, so the count is what the shortlist
 * shows at the top of that sort. Pages until the first deadline past the horizon.
 */
@Component
@RequiredArgsConstructor
class DeadlinesTrigger implements SuggestionTrigger {

    private final OfferQueryService offers;

    @Override
    public String key() {
        return SuggestionService.DEADLINES;
    }

    @Override
    public Optional<SuggestionCandidate> evaluate(SuggestionScope scope, SuggestionThresholds thresholds) {
        LocalDate horizon = LocalDate.now(ZoneId.systemDefault()).plusDays(thresholds.deadlineDays());
        ShortlistQuery query = new ShortlistQuery(
                null,
                ScoreFilter.ANY,
                List.of(),
                false,
                ShortlistSort.DEADLINE,
                StartWindow.of(null),
                RelatedFilter.ANY,
                null,
                true,
                false,
                null,
                null,
                ShortlistQuery.MAX_LIMIT);
        int count = 0;
        while (true) {
            // No topic is set, so nothing is embedded and nothing is paid: refuse any spend.
            var page = offers.shortlist(query, () -> false);
            for (ShortlistEntry entry : page.entries()) {
                LocalDate applyBy = entry.offer().applyBy();
                if (applyBy == null || applyBy.isAfter(horizon)) {
                    return candidate(count);
                }
                count++;
            }
            if (page.nextCursor() == null) {
                return candidate(count);
            }
            query = query.withCursor(page.nextCursor());
        }
    }

    private Optional<SuggestionCandidate> candidate(int count) {
        return count == 0 ? Optional.empty() : Optional.of(SuggestionCandidate.of(key(), count, Map.of()));
    }
}
