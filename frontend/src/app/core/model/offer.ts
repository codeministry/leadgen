/**
 * One listing, before anything judges it. Mirrors `de.codeministry.leadgen.offer.OfferView`.
 *
 * The enriched half — rate, workload, start, duration — arrives from the enrichment stage,
 * never from extraction: the newsletter states a rate in 0.0 % of offers. Every one of
 * those fields is therefore nullable, and a null means "not known", never "zero".
 *
 * So is most of the extracted half. A source that states no company, no location or no
 * date is the normal case, and a non-nullable type here would be a lie the first time the
 * screen renders one.
 */
export interface Offer {
  readonly id: number;
  readonly externalId: string | null;
  readonly title: string;
  readonly description: string | null;
  readonly url: string | null;
  readonly location: string | null;
  readonly portal: string | null;
  readonly agency: string | null;
  readonly publishedOn: string | null;
  readonly tags: readonly string[];

  readonly rateEur: number | null;
  readonly remotePercent: number | null;
  readonly startsOn: string | null;
  readonly duration: string | null;
  readonly workload: string | null;
  readonly language: string | null;
  /** The original ad as enrichment fetched it. Null when the fetch never succeeded. */
  readonly fullText: string | null;
  /** The folder the packaging stage wrote. Null until the offer clears the threshold. */
  readonly packageDir: string | null;
}

/**
 * What the pipeline knows about an offer beyond its fields. Both flags are reasons to
 * look, never reasons to discard: a failed enrichment leaves the offer in as incomplete,
 * and an unstated remote share survives the filter.
 */
export interface OfferFlags {
  readonly incomplete: boolean;
  readonly remoteUnknown: boolean;
}

/** One portal advertising an offer. A duplicate cluster names all of them. */
export interface OfferSource {
  readonly portal: string | null;
  readonly agency: string | null;
  readonly url: string | null;
}
