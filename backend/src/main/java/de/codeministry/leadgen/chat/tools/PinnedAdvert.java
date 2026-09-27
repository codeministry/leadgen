/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.chat.tools;

/**
 * One pinned offer's advert as the pinned lookup hands it over.
 *
 * @param offer the offer's id; named {@code offer}, not {@code offerId}, because the turn reads a
 *     top-level {@code offerId} as a returned row and this one sits inside a list
 * @param text  the advert without the portal's furniture, cut to the lookup's share per offer
 * @param cut   whether the text was cut, so the model does not quote a cut sentence as the whole
 */
public record PinnedAdvert(long offer, String text, boolean cut) {}
