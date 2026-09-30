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
 * What the pinned lookup hands the model: every pinned offer in the search's shape, in the order
 * they were pinned, and each one's advert beside it.
 *
 * @param offers  the pinned offers that still exist; the turn's ledger records each id
 * @param adverts one advert per offer, in the same order
 */
public record PinnedOfferResult(List<OfferHit> offers, List<PinnedAdvert> adverts) {}
