/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.chat.tools;

import java.time.Instant;

/**
 * One offer as the chat's tools hand it to the model: enough to name it and cite it, nothing
 * more.
 *
 * <p>Deliberately not {@code ShortlistEntry}. The entry carries the advert's full text, the
 * content blocks, the agency and the portal links, and every one of those is text a model
 * would read, pay for and could repeat. The id is what the grounding check holds an answer to;
 * the title, the source and the date are what make the id legible in a sentence.
 *
 * @param id       the offer's id, which is what the answer cites and the screen links
 * @param title    the advert's title as the shortlist shows it
 * @param source   the configured source it came in through, by its name in {@code sources.yaml}
 * @param cameIn   when the pipeline first saw it, which is the shortlist's "fresh" key
 * @param score    the score, or null while nothing has judged it
 * @param archived whether it sits in the archive rather than on the working list
 */
public record OfferHit(long id, String title, String source, Instant cameIn, Integer score, boolean archived) {}
