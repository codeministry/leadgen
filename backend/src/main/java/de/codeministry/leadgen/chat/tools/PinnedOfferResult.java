/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.chat.tools;

import java.util.List;

/**
 * The pinned offer as the turn hands it to the model: the search's shape for one offer, and its
 * advert.
 *
 * <p>{@code offers} is a list of one rather than a single field so the turn's ledger reads its id
 * the way it reads every search's, from {@code offers[].id}.
 *
 * @param offers      the offer, as the offer search returns it
 * @param advert      the advert without the portal's furniture, cut at {@link PinnedOfferLookup#ADVERT_CHARS}
 * @param advertCut   whether it was cut
 */
public record PinnedOfferResult(List<OfferHit> offers, String advert, boolean advertCut) {}
