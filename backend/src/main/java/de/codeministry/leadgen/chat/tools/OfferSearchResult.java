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
 * What the offer search tool answers: the first page of the shortlist for the filters it was
 * given, and how many the filters matched in all.
 *
 * <p>The count travels with the page because the page is capped. Shown twenty-five offers
 * without it, a model says "there are twenty-five", which is exactly the wrong number the chat
 * exists not to produce; with it, the number in the answer is the one beside the shortlist.
 *
 * @param matched how many offers the filters matched, the shortlist's own count
 * @param offers  the first of them, in the shortlist's order
 */
public record OfferSearchResult(int matched, List<OfferHit> offers) {}
