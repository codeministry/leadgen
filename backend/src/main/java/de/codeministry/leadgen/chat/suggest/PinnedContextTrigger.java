/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.chat.suggest;

import de.codeministry.leadgen.chat.ChatContextItem;
import de.codeministry.leadgen.chat.ChatContextKind;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Whatever the chat has pinned, the conversation's offer and its context chips, as one candidate
 * naming how many pins there are. Nothing to measure against, so no threshold: a pin is there or
 * it is not. Reads only the scope its caller resolved.
 */
@Component
class PinnedContextTrigger implements SuggestionTrigger {

    @Override
    public String key() {
        return SuggestionService.PINNED;
    }

    @Override
    public Optional<SuggestionCandidate> evaluate(SuggestionScope scope, SuggestionThresholds thresholds) {
        Set<Long> offers = new LinkedHashSet<>();
        if (scope.pinnedOfferId() != null) {
            offers.add(scope.pinnedOfferId());
        }
        int views = 0;
        for (ChatContextItem item : scope.context()) {
            if (item.kind() == ChatContextKind.OFFER) {
                if (item.offerId() != null) {
                    offers.add(item.offerId());
                }
            } else {
                views++;
            }
        }
        int count = offers.size() + views;
        return count == 0
                ? Optional.empty()
                : Optional.of(SuggestionCandidate.of(
                        key(),
                        count,
                        Map.of("offers", Integer.toString(offers.size()), "views", Integer.toString(views))));
    }
}
