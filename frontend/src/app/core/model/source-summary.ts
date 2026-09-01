export type SourceKind = 'imap' | 'rss' | 'file';

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
  readonly layer: ConfigLayer;
  readonly lastRunAt: string | null;
  readonly documents: number;
  readonly extracted: number;
  readonly announced: number | null;
  readonly survived: number;
}
