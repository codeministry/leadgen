import { Offer, OfferFlags, OfferSource } from './offer';
import { Score } from './score';

/**
 * One *project* on the shortlist, which is not the same thing as one listing:
 * `sources` holds every portal advertising it, collapsed by deduplication. The
 * concept has no name for this yet, so it gets one here.
 */
export interface ShortlistEntry {
  readonly offer: Offer;
  readonly score: Score;
  readonly flags: OfferFlags;
  readonly sources: readonly OfferSource[];
}
