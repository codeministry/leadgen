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
 * @param related how much of the working list a relatedness filter can reach, or null when
 *     this installation cannot answer one at all. **That null is the whole capability flag**,
 *     and what lets the screen leave the control out rather than offer a disabled one.
 * @param relatedTo the title of the offer a `similar=` filter is anchored on, so the chip can
 *     name it without a second request. Null unless that is what was asked for.
 */
export interface ShortlistPage {
    readonly entries: readonly ShortlistEntry[];
    readonly nextCursor: string | null;
    readonly matched: number;
    readonly unscored: number;
    readonly total: number;
    readonly portals: readonly string[];
    readonly related: RelatedCoverage | null;
    readonly relatedTo: string | null;
}

/**
 * How far the relatedness filter can see, in offers.
 *
 * Two counts and not a percentage, because the sentence built from them names offers. It is
 * what stops a short result reading as a quiet market while the index is still being filled:
 * most adverts simply have no vector yet. It stops being worth printing when the two meet.
 */
export interface RelatedCoverage {
    readonly readable: number;
    readonly total: number;
}

/** What the screen is asking for. The query string holds it, so a view stays a link. */
export interface ShortlistFilters {
    readonly q: string;
  /**
   * One of the three spellings of the score axis, and the server refuses two at once. A
   * band is a range whose boundaries are the configured thresholds; `minScore`/`maxScore`
   * are the same shape with the numbers in the request; `scoreState` asks whether there is
   * a score at all. The screen never produces two, because the three are one control group.
   */
    readonly band: string;
  /** Inclusive, and null rather than 0: zero is a score an offer can actually have. */
  readonly minScore: number | null;
  readonly maxScore: number | null;
  /** `any`, `scored` or `unscored`. The figure beside the list, as a filter. */
  readonly scoreState: string;
  /**
   * Every portal to include, empty for all of them. An offer matches on its own portal or
   * on any of its duplicates', which is why the dropdown lists those too.
   */
  readonly portals: readonly string[];
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
  readonly possibleDuplicates: boolean;
  /**
   * Words to find offers near. **Narrows and never reorders**: the list stays in whichever of
   * the six orders is selected, so the first row is not the best match — there is no such
   * thing here. The server refuses this together with `similarTo`, and this screen never
   * produces the pair.
   */
  readonly semantic: string;
  /** An offer to find offers near, or null. Costs no model call: both vectors are stored. */
  readonly similarTo: number | null;
}
