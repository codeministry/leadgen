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
 */
public record ContentReport(int considered, int segmented, int blocks, int fromCache, int requests, int undecided) {

    public static ContentReport skipped() {
        return new ContentReport(0, 0, 0, 0, 0, 0);
    }
}
