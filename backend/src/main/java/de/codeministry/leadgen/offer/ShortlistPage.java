package de.codeministry.leadgen.offer;

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
 *     offset: an offset re-reads and re-sorts everything before it on every page, and it
 *     skips or repeats a row whenever a run rewrites a score between two requests.
 * @param matched how many offers the filters match, which is what "12 of 96" counts.
 * @param unscored how many of those carry no score. Counted here rather than in the
 *     browser, where it counted the loaded pages and therefore shrank as you scrolled.
 * @param total the whole shortlist, so the same sentence can say what it was narrowed from.
 * @param portals every portal on the shortlist, not merely on this page. Derived from the
 *     page it would be a filter that offers fewer choices the further you scroll.
 */
public record ShortlistPage(
        List<ShortlistEntry> entries,
        String nextCursor,
        int matched,
        int unscored,
        int total,
        List<String> portals) {}
