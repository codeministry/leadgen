/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.offer;

/**
 * A shortlist request that names something this application does not offer.
 *
 * <p>One type for the sort, the start window and the cursor, because all three are the same
 * failure: a string arrived from outside, it is not one of the values that exist, and the
 * honest answer is a 400 naming what was asked for. Refused at the edge, so nothing that was
 * never vetted reaches the SQL.
 *
 * <p>The alternative each time is worse. A sort nobody defined, accepted and silently
 * replaced by the default, is a screen that quietly ignores a control. A cursor minted under
 * one sort and replayed under another reads the leading value as the wrong kind of number and
 * returns an arbitrary slice with no error anywhere. And falling back to the first page is
 * worse still here: what asks for the next page is an {@code IntersectionObserver} sentinel,
 * so "start from the top" appends page one underneath page one, which reads as a data bug and
 * points nowhere.
 */
public class BadShortlistRequest extends RuntimeException {

    public BadShortlistRequest(String message) {
        super(message);
    }
}
