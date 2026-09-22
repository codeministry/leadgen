/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.offer;

import de.codeministry.leadgen.retrieval.SemanticFilter;
import java.util.List;

/**
 * One page of the shortlist, and what the whole of it looks like.
 *
 * <p>The list used to come down entire and the browser filtered it, which made a filtered
 * view survive a reload as a link and cost one query. At 2,219 survivors that answer was
 * 3 MB, and it grows with every newsletter. So the filtering moved into SQL — where it now
 * exists once instead of twice — and the query string still drives it, so a filtered view
 * is still a link somebody can send.
 *
 * @param nextCursor what to ask for to continue, or null at the end. Keyset rather than an
 *                   offset: an offset re-reads and re-sorts everything before it on every page, and it
 *                   skips or repeats a row whenever a run rewrites a score between two requests.
 * @param matched    how many offers the filters match, which is what "12 of 96" counts.
 * @param unscored   how many of those carry no score. Counted here rather than in the
 *                   browser, where it counted the loaded pages and therefore shrank as you scrolled.
 * @param total      the whole shortlist, so the same sentence can say what it was narrowed from.
 * @param portals    every portal on the shortlist, not merely on this page. Derived from the
 *                   page it would be a filter that offers fewer choices the further you scroll.
 * @param related    how much of the working list a relatedness filter can reach, or <b>null when
 *                   this installation cannot answer one at all</b> — which is the whole capability
 *                   flag, and what lets the browser leave the control out instead of offering one
 *                   the server would refuse. Server-counted over the working list, like every
 *                   other number printed beside this list: built from the loaded page it would
 *                   shrink as the reader scrolled, which is the defect that moved `matched` here.
 * @param relatedTo  the title of the offer a `similar=` filter is anchored on, so the chip can
 *                   name it without a second request. Null unless that is what was asked for.
 */
public record ShortlistPage(
        List<ShortlistEntry> entries,
        String nextCursor,
        int matched,
        int unscored,
        int total,
        List<String> portals,
        SemanticFilter.RelatedCoverage related,
        String relatedTo) {}
