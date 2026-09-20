/** Whatever `type` a source declares. A new connector is a YAML block, not an enum. */
export type SourceKind = string;

/** Which of the two configuration layers this source's definition came from. */
export type ConfigLayer = 'default' | 'config-dir';

/**
 * One row on the sources screen. `announced` versus `extracted` is the only
 * check nothing else can make: a selector that stops matching loses offers, and
 * fewer offers looks exactly like a quiet day on the market.
 */
export interface SourceSummary {
    readonly id: string;
    readonly kind: SourceKind;
    readonly enabled: boolean;
    readonly lastRunAt: string | null;
    readonly documents: number;
    readonly extracted: number;
    readonly announced: number | null;
    readonly survived: number;
}

/**
 * What `/api/v1/sources` answers: the file that defines the sources, and the sources.
 *
 * <p>The layer is on the envelope and no longer on the row. It is one probe for the whole
 * file — the two configuration layers override each other file by file and never key by key —
 * so a badge per row was asserting something that cannot differ between two rows. Stated once
 * above the table it is simply true, and the table lost a column, which is the only structural
 * relief a seven-column table has on a phone.
 *
 * The file's *path* is deliberately not here: it is a personal datum and this payload is behind
 * every screenshot of the screen. It lives in the panel one deliberate click opens.
 */
export interface SourcesView {
  readonly file: string;
  readonly layer: ConfigLayer;
  readonly sources: readonly SourceSummary[];
}
