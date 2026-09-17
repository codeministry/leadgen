/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.retrieval;

import de.codeministry.leadgen.content.ContentText;

/**
 * What goes into a retrieval vector, defined once.
 *
 * <p>One place, because {@code docs/samples/measure_embeddings.ts} mirrors it character for
 * character and the measurement is only worth something while the two agree. The teaser half of
 * that script mirrors {@code OfferEmbedder.text()} for the same reason.
 */
public final class AdvertText {

    /**
     * How much of an advert is embedded.
     *
     * <p><b>Not a server limit, and not a configuration key.</b> Measured 2026-09-17: against
     * {@code qwen3-embedding:8b} two texts differing only in their last five hundred characters
     * produced different vectors at every length up to 24000, so nothing truncates this on the
     * way out. The cap is a choice about dilution instead — the same run moved two texts that
     * differ in what the job actually is, and agree on everything around it, from 0.49 apart to
     * 0.91 alike once four thousand characters of shared boilerplate sat in front of them.
     *
     * <p>On the corpus it was measured against it never binds: the longest de-furnitured advert
     * of 252 was under this, with a median of 2298 characters. It is here for the portal that
     * writes longer ones, and it is a constant rather than a key for the reason
     * {@code DESCRIPTION_CHARS} is: a number tuned once and read by nobody afterwards is the
     * kind of configuration that lies.
     */
    static final int ADVERT_CHARS = 6000;

    private AdvertText() {}

    /**
     * The title, the location and the advert, as {@code ContentText} hands it to the judge and
     * the packager: the CONTENT blocks when the advert was segmented, {@code full_text} when it
     * was not.
     *
     * <p><b>The de-furnitured text and not {@code full_text}, and that is what makes the length
     * safe.</b> The dilution the cap above guards against is caused by the part the content
     * stage removes — on the measured corpus 1631 furniture blocks against 2701 kept ones, about
     * fourteen hundred characters an advert. Embedding the whole page would put all of it back
     * and make every advert from one agency look alike.
     *
     * <p>The location is in for the reason {@code OfferEmbedder} gives: "Nürnberg" and "Remote
     * und Nürnberg" are one place written twice and two strings compared once.
     */
    public static String of(String title, String location, String contentBlocks, String fullText) {
        StringBuilder text = new StringBuilder(title == null ? "" : title.strip());
        if (location != null && !location.isBlank()) {
            text.append('\n').append(location.strip());
        }
        String advert = ContentText.of(contentBlocks, fullText);
        if (advert != null && !advert.isBlank()) {
            String stripped = advert.strip();
            text.append('\n')
                    .append(stripped.length() <= ADVERT_CHARS ? stripped : stripped.substring(0, ADVERT_CHARS));
        }
        return text.toString();
    }
}
