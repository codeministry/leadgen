/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.fields;

/**
 * What one field-extraction pass did.
 *
 * @param considered offers whose start, duration and deadline had not been read this way yet.
 * @param extracted  offers the pass finished with. Lower than {@code considered} means a
 *                   configured model did not answer for the difference, and those offers are
 *                   due again.
 * @param requests   offers a model was actually asked about. One call per offer, and equal to
 *                   {@code considered} whenever a model is configured — there is no cache in
 *                   front of this one, because a start date is a fact about one advert and not
 *                   about a paragraph that repeats across a portal.
 * @param stated     offers that turned out to say something about at least one of the three.
 *                   <b>Watch this one.</b> It is the share this stage is worth, and a sudden
 *                   drop is what a model that has started answering null to everything looks
 *                   like from here.
 * @param rejudged   offers whose score was invalidated because a value actually changed. The
 *                   price of the pass, in language-model calls somebody else pays later.
 */
public record FieldsReport(int considered, int extracted, int requests, int stated, int rejudged) {

    public static FieldsReport skipped() {
        return new FieldsReport(0, 0, 0, 0, 0);
    }
}
