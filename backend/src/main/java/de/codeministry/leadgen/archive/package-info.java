/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */

/**
 * What is no longer on the working list.
 *
 * <p>An axis, not a verdict. The filter says whether an advert is worth answering; this says
 * whether it is on today's list. Age used to be reported as a rejection, which is what
 * somebody read when they asked why an offer was missing.
 *
 * <p>It cannot be a value in {@code offer.status}: the filter reads the whole table with no
 * {@code WHERE} and writes a verdict onto every row, so an {@code ARCHIVED} status would be
 * overwritten by {@code PASSED} on the next run, silently and only for the offers that still
 * pass. Two columns, because there are four states: archived by age, archived by hand, on the
 * list, and <em>deliberately</em> back on it, which is what stops the age pass taking a
 * restored offer off again the next morning.
 *
 * <p>The pass reconciles rather than seals: rows it archived itself come back when the window
 * widens, rows a person archived never do.
 */
package de.codeministry.leadgen.archive;
