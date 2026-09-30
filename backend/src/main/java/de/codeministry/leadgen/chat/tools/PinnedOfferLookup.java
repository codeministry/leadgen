/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.chat.tools;

import de.codeministry.leadgen.content.ContentBlock;
import de.codeministry.leadgen.offer.OfferQueryService;
import de.codeministry.leadgen.offer.OfferView;
import de.codeministry.leadgen.offer.ShortlistEntry;
import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The offers pinned to a conversation, read at the start of each of its turns.
 *
 * <p><b>Not a tool the model can call</b>, and deliberately without {@code @Tool}: the turn runs it
 * before the model is asked, so the offer is in front of the model whether or not it would have
 * thought to look. It is recorded like a tool call all the same — a step, a {@code chat_tool_call}
 * row, the id in the turn's ledger — because that is what makes a citation of the pinned offer a
 * link.
 *
 * <p>It reads through {@link OfferQueryService#find}, the offer detail's own read, and hands back
 * what the offer search hands back for one offer ({@link OfferHit}) plus the advert without the
 * portal's furniture. The advert is here and not in the search because a question about one offer
 * is a question about its text; a page of twenty-five adverts would be a page nobody reads.
 *
 * <p>Read-only: the one dependency is a read service.
 */
@Component
@RequiredArgsConstructor
public class PinnedOfferLookup {

    /** The name the lookup is recorded under, in the step line and in {@code chat_tool_call}. */
    public static final String NAME = "pinned_offer";

    /**
     * The longest advert handed over, in characters.
     *
     * <p>About 1,500 tokens: a long advert with its requirements and conditions fits, and it still
     * leaves a local model with a small context room for the conversation, the other tools' pages
     * and the answer. Past this an advert is boilerplate more often than substance, and the result
     * says it was cut, so the model does not quote a truncated sentence as the whole.
     */
    static final int ADVERT_CHARS = 6000;

    private final OfferQueryService offers;

    /**
     * Every pinned offer of the conversation, in the order they were pinned (ISC-453) — each read
     * whatever its status, because the reader pinned it.
     *
     * <p>The advert budget is shared: ten pinned offers get a tenth of {@link #ADVERT_CHARS} each,
     * so ten pins cost the model's context what one did, and each advert says whether it was cut.
     *
     * @throws IllegalArgumentException when none of the offers exists any more; the turn hands that
     *     to the model
     */
    public PinnedOfferResult lookup(List<Long> offerIds) {
        List<ShortlistEntry> entries = offerIds.stream()
                .map(offers::find)
                .flatMap(java.util.Optional::stream)
                .toList();
        if (entries.isEmpty()) {
            throw new IllegalArgumentException("the pinned offers " + offerIds + " no longer exist");
        }
        int share = ADVERT_CHARS / entries.size();
        List<PinnedAdvert> adverts = entries.stream()
                .map(entry -> {
                    String advert = advert(entry);
                    boolean cut = advert.length() > share;
                    return new PinnedAdvert(entry.offer().id(), cut ? advert.substring(0, share) : advert, cut);
                })
                .toList();
        return new PinnedOfferResult(entries.stream().map(OfferSearchTool::hit).toList(), adverts);
    }

    /**
     * The advert's content blocks when they were read, else its whole text, else its summary —
     * the same fallback {@code ContentText.of} makes, over the blocks the detail read already parsed.
     */
    static String advert(ShortlistEntry entry) {
        String kept = entry.content().stream()
                .filter(ContentBlock::isContent)
                .map(ContentBlock::text)
                .collect(Collectors.joining("\n\n"));
        if (!kept.isBlank()) {
            return kept;
        }
        OfferView offer = entry.offer();
        if (offer.fullText() != null && !offer.fullText().isBlank()) {
            return offer.fullText();
        }
        return offer.description() == null ? "" : offer.description();
    }
}
