/**
 * One listing, before anything judges it. Field names mirror the `offer` table
 * and `ExtractedOffer` so the eventual endpoint needs no rename on this side.
 *
 * The enriched half — rate, workload, start, duration — arrives from the
 * enrichment stage, never from extraction: the newsletter states a rate in 0.0 %
 * of offers. Every one of those fields is therefore nullable, and a null means
 * "not known", never "zero".
 */
export interface Offer {
  readonly id: string;
  readonly externalId: string;
  readonly title: string;
  readonly description: string;
  readonly url: string;
  readonly location: string | null;
  readonly portal: string | null;
  readonly agency: string | null;
  readonly publishedOn: string;
  readonly tags: readonly string[];

  readonly rateEur: number | null;
  readonly remotePercent: number | null;
  readonly startsOn: string | null;
  readonly duration: string | null;
  readonly language: 'de' | 'en' | null;
}

/**
 * What the pipeline knows about an offer beyond its fields. Both flags are
 * reasons to look, never reasons to discard: a failed enrichment leaves the
 * offer in as incomplete, and an unstated remote share survives the filter.
 */
export interface OfferFlags {
  readonly incomplete: boolean;
  readonly remoteUnknown: boolean;
}

/** One portal advertising an offer. A duplicate cluster names all of them. */
export interface OfferSource {
  readonly portal: string;
  readonly agency: string | null;
  readonly url: string;
}
