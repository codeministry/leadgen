import {ContentBlock, Offer, OfferFlags, OfferSource} from './offer';
import {Score} from './score';

/**
 * One *project* on the shortlist, which is not the same thing as one listing:
 * `sources` holds every portal advertising it, collapsed by deduplication. The
 * concept has no name for this yet, so it gets one here.
 *
 * `content` is the advert read into blocks, and only the detail endpoint fills it — the list
 * shares the server's row mapper and would otherwise carry a second copy of every advert for
 * something no card renders. So empty means two things that want the same answer: this came
 * from the list, or nothing has read the advert that way yet. Both fall back to
 * `offer.fullText`.
 */
export interface ShortlistEntry {
    readonly offer: Offer;
    readonly score: Score;
    readonly flags: OfferFlags;
    readonly sources: readonly OfferSource[];
  readonly content: readonly ContentBlock[];
}
