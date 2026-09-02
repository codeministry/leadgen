/**
 * The last run as the database remembers it. Mirrors
 * `de.codeministry.leadgen.analytics.LastRunView`.
 *
 * Deliberately **not** an `IngestReport`, and the difference is visible on screen. A report
 * is what a run handed back to the browser that started it; this is what survived the run
 * in two tables. Two things are genuinely not persisted and are therefore absent rather
 * than reconstructed: the per-document `announced` breakdown — `source_run` holds one row
 * per source, not per document — and the digest path, of which only "was one written" is
 * recorded.
 *
 * Without this, the dashboard knew about a run only if this browser had started one:
 * measured on 2026-09-02, a run six minutes old and the screen still saying "No run yet".
 * After a scheduled pass that is every morning.
 */
export interface LastRunSource {
  readonly sourceId: string;
  readonly documents: number;
  readonly extracted: number;
  /** Rows the upsert touched, insert or update alike. Not new rows. */
  readonly written: number;
  /** Null when the source states no count to check against, which is most of them. */
  readonly announced: number | null;
  /** False only when the source stated a count and the extraction missed it. */
  readonly complete: boolean;
}

export interface LastRunView {
  /** What tells the reader whether this is tonight's pass or their own click. */
  readonly finishedAt: string;
  /** `COMPLETE`, or `AWAITING_BATCH` while a batched run's scores are still in flight. */
  readonly status: string;
  /** Which judge produced the scores. A run without its scale is a number with nothing behind it. */
  readonly scoreModel: string | null;
  readonly extracted: number;
  readonly written: number;
  /** The standing total inside the deduplication window, not the rows this run moved. */
  readonly merged: number;
  /** Offers rejected per hard-filter stage, keyed by the stage name. */
  readonly removed: Readonly<Record<string, number>>;
  readonly filterConsidered: number;
  readonly filterPassed: number;
  readonly scored: number;
  readonly shortlisted: number;
  readonly review: number;
  readonly packaged: number;
  readonly digestWritten: boolean;
  readonly sources: readonly LastRunSource[];
}
