/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.chat.tools;

import java.util.List;

/**
 * What the semantic search answers: the offers nearest the question in meaning, nearest first,
 * or the sentence saying why this installation cannot search by meaning at all.
 *
 * <p>The sentence is a field rather than an empty list, because an empty list reads to a model
 * as "nothing in the corpus is like that", a statement about the market, when the truth is a
 * statement about the installation. There is no {@code matched} count beside the list, unlike
 * the offer search: nearness has no edge, so every offer with a vector matches at some distance
 * and the number would only be the size of the index.
 *
 * @param unavailable why nothing was searched, or null when the search ran
 * @param offers      the nearest offers, working list and archive together, each flagged
 */
public record SemanticSearchResult(String unavailable, List<OfferHit> offers) {

    static SemanticSearchResult absent(String reason) {
        return new SemanticSearchResult(reason, List.of());
    }
}
