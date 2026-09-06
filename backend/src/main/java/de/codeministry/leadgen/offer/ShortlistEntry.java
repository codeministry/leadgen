/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.offer;

import de.codeministry.leadgen.content.ContentBlock;

import java.util.List;

/**
 * One *project* on the shortlist, which is not the same thing as one listing.
 *
 * <p>{@code sources} holds every portal advertising it, collapsed by deduplication — 12.3
 * % of the measured corpus reaches the pipeline more than once, and a shortlist that shows
 * the same project three times is a shortlist nobody finishes reading.
 *
 * <p>{@code content} is the advert read into blocks, and it is <b>populated by the detail
 * query alone</b>. The list uses the same row mapper, so a block list carried along with it
 * would put a second copy of every advert into a response the repository already measures in
 * megabytes — for a column no card renders. Empty therefore means two things that want the
 * same answer: this is the list, or nothing has read the advert that way yet. Both fall back
 * to {@code offer.fullText}.
 */
public record ShortlistEntry(
    OfferView offer,
    OfferScoreView score,
    OfferFlags flags,
    List<OfferSourceRef> sources,
    List<ContentBlock> content) {
}
