/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */

/**
 * One project advertised by several portals, collapsed into one entry that names them.
 *
 * <p>Not the upsert in {@code OfferStore}. That one collapses a <em>listing</em> seen twice,
 * which is what re-reading a newsletter produces. This collapses a <em>project</em>, which is
 * 12.3 % of the measured corpus.
 *
 * <p>The fingerprint is the normalized title and nothing else, and that is measured rather
 * than lazy. Adding the one other field that exists at this point, the stated location,
 * collapses 111 instead of 159, and the 48 it gives up are overwhelmingly correct merges lost
 * to the same ad writing "Nürnberg" in one portal and "Remote und Nürnberg" in the next. <b>A
 * field that is present is not the same as a field that is comparable.</b> The consequence is
 * accepted rather than hidden: two different projects sharing a title do merge.
 *
 * <p>Idempotent by construction. The primary is recomputed from the group every run, so a
 * second run assigns exactly what the first did and a listing arriving later attaches to the
 * primary already there instead of starting a rival cluster.
 */
package de.codeministry.leadgen.dedupe;
