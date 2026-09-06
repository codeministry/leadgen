/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.content;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Reading {@code offer.content_blocks} back, for everything that wants the advert without
 * the portal around it.
 *
 * <p>One place, because there are four readers — the deterministic scorer, the judge's
 * description of an offer, the packager's skill haystack and the read side — and four
 * implementations of "join the content blocks" would drift the first time a kind is added.
 *
 * <p><b>A null column means the advert has not been read this way</b>, and every caller falls
 * back to {@code full_text}. That is what lets the stage be switched on against a table full
 * of offers without a migration and without a day where half the shortlist is scored against
 * a different text than the other half.
 */
@Slf4j
public final class ContentText {

    private static final ObjectMapper JSON =
        new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final TypeReference<List<ContentBlock>> BLOCKS = new TypeReference<>() {
    };

    private ContentText() {
    }

    /**
     * The blocks, or an empty list when the column is null or unreadable.
     *
     * <p>Unknown properties are tolerated here and nowhere else in this repository: the
     * configuration binder is strict because a misspelled key silently disables a filter,
     * while this reads rows <em>this application wrote</em>. A build that added a field would
     * otherwise be unable to read the rows written by the build before it.
     */
    public static List<ContentBlock> parse(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return JSON.readValue(json, BLOCKS);
        } catch (com.fasterxml.jackson.core.JacksonException e) {
            log.warn("content_blocks could not be read: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * The advert without the portal's furniture, or {@code fullText} unchanged when nothing
     * has decided anything about it yet.
     *
     * <p>Blocks are rejoined with a blank line between them, which is what separated them: a
     * block is a Markdown block, so the result is Markdown again and every reader downstream
     * — a regex, a fold, a renderer — sees the same shape it saw before.
     */
    public static String of(String json, String fullText) {
        List<ContentBlock> blocks = parse(json);
        if (blocks.isEmpty()) {
            return fullText;
        }
        String kept = blocks.stream()
            .filter(ContentBlock::isContent)
            .map(ContentBlock::text)
            .collect(Collectors.joining("\n\n"));
        // An advert that is entirely furniture is a reading nobody should act on. Falling
        // back to the whole text scores it on what is actually there rather than on nothing,
        // and `content_undecided` is where such a page shows up.
        return kept.isBlank() ? fullText : kept;
    }
}
