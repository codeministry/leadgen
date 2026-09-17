/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.ask;

/**
 * What one advert says about one question.
 *
 * @param question the question that was asked, by its wire name.
 * @param stated   whether the advert says anything about it at all. <b>The distinction this
 *                 record exists for.</b> "The advert is silent on the rate" and "the advert
 *                 offers 85 EUR" are different answers, and a screen that renders a shrug the
 *                 same way it renders a fact is a screen that invites the reader to fill the
 *                 gap themselves. Same shape as an unscored offer, which is not a zero.
 * @param answer   one or two sentences, in the language of the advert, or null when
 *                 {@code stated} is false.
 * @param quote    the sentence from the advert the answer rests on, verbatim, or null. <b>A
 *                 claim without one is dropped rather than shown</b> — see {@link AdvertAsker}.
 * @param model    which model answered, so two answers that disagree can be told apart.
 */
public record AdvertAnswer(String question, boolean stated, String answer, String quote, String model) {

    /** The advert says nothing about this, which is an answer and not a failure. */
    public static AdvertAnswer silent(AdvertQuestion question, String model) {
        return new AdvertAnswer(question.key(), false, null, null, model);
    }
}
