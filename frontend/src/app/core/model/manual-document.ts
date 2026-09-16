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
 * `offer` is null when nothing could be read from the file at all — a pasted ad under a
 * source that asks for no fallback, or one where no model answered.
 */
export interface PendingDocument {
    readonly name: string;
    readonly size: number;
    readonly uploadedAt: string;
    readonly text: string;
    readonly offer: ExtractedOffer | null;
  /**
   * The fields a language model read, empty whenever the frontmatter was readable.
   *
   * The names are the server's eight-field contract and not this interface's spelling:
   * the date is `published` here and `publishedOn` on `ExtractedOffer`. They are the keys
   * of the block the pipeline maps, which is what the server marks.
   */
  readonly fromModel: readonly string[];
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
