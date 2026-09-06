/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.content;

/**
 * One block of an ad, with what it was decided to be.
 *
 * <p><b>The text is carried here rather than resolved from the digest at read time.</b> It
 * costs a second copy of {@code full_text} per offer, and it buys three things: the browser
 * needs no splitter of its own, so there is no second implementation to drift; the indices
 * cannot slip, because they are stored beside the text they belong to; and the row is a
 * record of what was decided <em>for this offer</em>, which a later change of mind in the
 * shared cache cannot rewrite behind somebody's back.
 *
 * @param index  position in the ad, so a reader can be shown a hidden run exactly where it was
 * @param text   the block's Markdown, unedited
 * @param kind   what it is, {@link ContentKind#CONTENT} unless something decided otherwise
 * @param reason one short sentence, in English, or null when nothing had anything to say
 * @param by     who decided
 */
public record ContentBlock(int index, String text, ContentKind kind, String reason, Decider by) {

    /**
     * A block nobody has decided anything about. The default is deliberately the visible
     * one: without a model, without a rule and without a cache entry, the reader still sees
     * the whole ad.
     */
    public static ContentBlock undecided(int index, String text) {
        return new ContentBlock(index, text, ContentKind.CONTENT, null, Decider.DEFAULT);
    }

    public boolean isContent() {
        return kind.isContent();
    }
}
