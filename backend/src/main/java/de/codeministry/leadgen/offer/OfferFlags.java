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
 * What the pipeline knows about an offer beyond its fields.
 *
 * <p>Both are reasons to look, never reasons to discard. A failed enrichment leaves the
 * offer in as incomplete, and an unstated remote share survives the filter.
 *
 * @param incomplete        the ad was not reachable or not readable, so the enriched half is
 *                          missing. `enriched_at` set with no note is the only combination that means
 *                          complete.
 * @param possibleDuplicate the similarity pass found an older offer close enough to be the
 *                          same project and not close enough to merge. The row is still its own offer and
 *                          still on the working list; what the badge asks for is a second pair of eyes.
 */
public record OfferFlags(boolean incomplete, boolean remoteUnknown, boolean possibleDuplicate) {}
