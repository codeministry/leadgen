/** Mirrors `de.codeministry.leadgen.ingest.ExtractedOffer`. */
export interface ExtractedOffer {
  readonly externalId: string | null;
  readonly title: string | null;
  readonly description: string | null;
  readonly url: string | null;
  readonly location: string | null;
  readonly portal: string | null;
  readonly agency: string | null;
  readonly publishedOn: string | null;
  readonly tags: readonly string[];
  readonly fingerprint: string | null;
}

/**
 * One upload waiting for review. Mirrors `PendingDocument`.
 *
 * `offer` is null when the file has no frontmatter — a pasted ad, which is exactly the
 * case the review screen exists for.
 */
export interface PendingDocument {
  readonly name: string;
  readonly size: number;
  readonly uploadedAt: string;
  readonly text: string;
  readonly offer: ExtractedOffer | null;
  readonly duplicateOfId: number | null;
  readonly duplicateOfTitle: string | null;
}

/** The eight fields as the operator corrected them. Written back into the file. */
export interface ManualOfferFields {
  readonly title: string;
  readonly url: string | null;
  readonly description: string | null;
  readonly location: string | null;
  readonly portal: string | null;
  readonly agency: string | null;
  readonly published: string | null;
  readonly tags: readonly string[];
}
