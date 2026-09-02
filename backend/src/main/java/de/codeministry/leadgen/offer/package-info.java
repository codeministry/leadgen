/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */

/**
 * The read side: the whole offer row as a person reads it.
 *
 * <p>Read-only, and separate from the stages that write. Each of those owns a narrow slice of
 * the row; this owns all of it.
 *
 * <p>The working set is three parts — {@code status = 'PASSED'}, no {@code duplicate_of_id},
 * no {@code archived_at} — and it has to be all three at every site that counts survivors. The
 * funnel needs it on <b>both</b> sides of the subtraction, or the rail reports a negative
 * number of survivors, which it has.
 *
 * <p>Paged by keyset and never by offset: an offset re-reads and re-sorts everything before it
 * on every page, and skips or repeats a row whenever a run rewrites a score between two
 * requests. The key is the whole sort tuple, because score alone is not unique and a page
 * boundary falling inside a tie loses rows.
 *
 * <p>Every number printed beside the list is counted here, over the match, and never derived
 * from the loaded page. The detail is deliberately <em>not</em> restricted to survivors: it is
 * also how somebody opens a rejected offer and asks whether the rule was right.
 */
package de.codeministry.leadgen.offer;
