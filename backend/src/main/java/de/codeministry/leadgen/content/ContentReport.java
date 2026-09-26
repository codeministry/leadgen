/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.content;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * What one content pass did.
 *
 * @param considered offers whose ad was fetched and had not been read this way yet.
 * @param segmented  offers that came out of the pass with a block list.
 * @param blocks     blocks decided, over all of them.
 * @param fromCache  blocks a rule or an earlier offer had already decided. This is the
 *                   number the whole economics rests on: a portal's report dialog is
 *                   byte-identical in every one of its ads, so on a warm cache this is
 *                   nearly everything and `requests` is nearly nothing.
 * @param requests   offers a model was actually asked about. One call per offer, never one
 *                   per block.
 * @param undecided  blocks nobody had a label for, so they stayed visible. <b>Watch this
 *                   one.</b> It is what a changed portal markup looks like from here — a
 *                   number that moves, rather than a shortlist that quietly starts hiding
 *                   the wrong half of an advert.
 * @param width      the width the stage's bounded loop actually ran at: {@code 1} when it ran
 *                   sequentially, was skipped, had nothing due or no model to ask. The
 *                   {@code pipeline_stage} note and the {@code " at width N"} of the log line
 *                   are both read off this one value. Not part of the JSON a run answers with:
 *                   a response field is part of the API, and this one is already on the stage
 *                   row.
 */
public record ContentReport(
        int considered,
        int segmented,
        int blocks,
        int fromCache,
        int requests,
        int undecided,
        @JsonIgnore int width) {

    /** At width 1, which is what every caller outside the stage's own run means. */
    public ContentReport(int considered, int segmented, int blocks, int fromCache, int requests, int undecided) {
        this(considered, segmented, blocks, fromCache, requests, undecided, 1);
    }

    public static ContentReport skipped() {
        return new ContentReport(0, 0, 0, 0, 0, 0);
    }
}
