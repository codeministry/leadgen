/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.fields;

import com.fasterxml.jackson.annotation.JsonIgnore;

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
 * @param width      the width the stage's bounded loop actually ran at: {@code 1} when it ran
 *                   sequentially, was skipped, had nothing due or no model to ask. The
 *                   {@code pipeline_stage} note and the {@code " at width N"} of the log line
 *                   are both read off this one value. Not part of the JSON a run answers with:
 *                   a response field is part of the API, and this one is already on the stage
 *                   row.
 */
public record FieldsReport(
        int considered,
        int extracted,
        int requests,
        int stated,
        int rejudged,
        @JsonIgnore int width) {

    /** At width 1, which is what every caller outside the stage's own run means. */
    public FieldsReport(int considered, int extracted, int requests, int stated, int rejudged) {
        this(considered, extracted, requests, stated, rejudged, 1);
    }

    public static FieldsReport skipped() {
        return new FieldsReport(0, 0, 0, 0, 0);
    }
}
