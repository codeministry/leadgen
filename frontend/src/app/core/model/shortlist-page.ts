import {ShortlistEntry} from './shortlist-entry';

/**
 * Mirrors `de.codeministry.leadgen.offer.ShortlistPage`.
 *
 * @param nextCursor what to ask for to continue, null at the end.
 * @param matched how many offers the filters match; `total` is what they were narrowed from.
 * @param unscored how many of the matched carry no score. Server-side, because counted from
 *     the loaded entries it shrank as you scrolled while reading as a claim about the list.
 * @param portals every portal on the shortlist, not merely on this page — a filter built
 *     from the loaded page would offer fewer choices the further you scroll.
 */
export interface ShortlistPage {
    readonly entries: readonly ShortlistEntry[];
    readonly nextCursor: string | null;
    readonly matched: number;
    readonly unscored: number;
    readonly total: number;
    readonly portals: readonly string[];
}

/** What the screen is asking for. The query string holds it, so a view stays a link. */
export interface ShortlistFilters {
    readonly q: string;
    readonly band: string;
    readonly portal: string;
    /**
     * Which side of the archive to read. Not a band: a band is a range of scores, and this
     * decides which set the bands are applied to. The server counts `total` and the portal
     * list over the same side, so the sentence beside the list is about what is on screen.
     */
    readonly archived: boolean;
  /**
   * Which order, which is also which cursor: the server composes the ORDER BY and the
   * keyset comparison from one expression, so a cursor minted under one sort is refused
   * under another. That is why this belongs in the filters rather than beside them — the
   * `opened` event already clears `entries` and `cursor` on any change here, which is
   * exactly what a sort change needs and what stops a mismatched cursor being sent at all.
   */
  readonly sort: string;
  /**
   * When the engagement starts, as one of `any`, `now`, `soon`, `later`, `unknown`. Four
   * values that partition the set, and `unknown` is one of them on purpose: most adverts
   * name no resolvable day, so a window without it would hide most of the list invisibly.
   */
  readonly startWindow: string;
  /** The committed minimum length in months, or 0 for no minimum. */
  readonly minMonths: number;
  /** Only offers whose application deadline has not passed, plus those that stated none. */
  readonly deadlineOpen: boolean;
}
