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
 * What a block of a fetched ad is.
 *
 * <p><b>This enum is the model's contract</b>, exactly as {@code Judge.JUDGED} is the
 * judge's. A kind the model invents is dropped rather than stored, because a label nobody
 * can render is a block that silently disappears from the screen.
 *
 * <p>Every value names something a person can point at in a real ad. That is the bar: a
 * taxonomy finer than what a reader would distinguish is a taxonomy a small model answers
 * inconsistently, and the inconsistency lands in a cache that is meant to be permanent.
 */
public enum ContentKind {

    /**
     * The advert itself. The default, and what an undecided block is — nothing is ever
     * hidden without a positive decision to hide it.
     */
    CONTENT,

    /**
     * Portal furniture: meta rows, "Apply now", "Save to watchlist", "Print", "Report".
     */
    CHROME,

    /**
     * A dialog and its labels: the report-project dialog, the application form.
     */
    FORM,

    /**
     * The portal's own skill and category tag cloud, which is not what the ad asked for.
     */
    TAXONOMY,

    /**
     * The recruiter's standing signature: postal address, HRB, privacy link, groups.
     */
    AGENCY,

    /**
     * Disclaimers, privacy notices, equal-opportunity boilerplate.
     */
    LEGAL;

    /**
     * Whether a block of this kind is part of the advert a person came to read.
     */
    public boolean isContent() {
        return this == CONTENT;
    }
}
