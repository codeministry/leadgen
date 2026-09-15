/**
 * A pass that is still going. Mirrors `de.codeministry.leadgen.analytics.CurrentRunView`.
 *
 * Deliberately **not** a `LastRunView` with a flag. That one reports finished runs only,
 * because a `RUNNING` row carries zeros and under the heading "last run" would claim a pass
 * that found nothing. This one carries no counts at all — a start time, a model and the
 * stage — so there is nothing on it to mistake for a result.
 *
 * It exists because a pass takes eleven minutes on the deployed corpus and nothing said so:
 * the run button answered 409 with a sentence nobody saw, and the dashboard went on showing
 * last night's numbers as though today's click had done nothing.
 */
export interface CurrentRunView {
  readonly id: number;
  /** ISO instant in UTC. */
  readonly startedAt: string;
  /** Which judge this pass is scoring with, or null when none is configured. */
  readonly scoreModel: string | null;
  /**
   * The stage the run is in, or null in the moment between opening the row and entering the
   * first stage. The server's own names — `DEDUPE`, `ENRICH`, `INGEST <source>` — and they
   * stay English in both languages, like every other sentence this API writes.
   */
  readonly stage: string | null;
  /** 1-based, or null for that same moment. */
  readonly stagePosition: number | null;
  /** One stage per enabled source plus the fixed ones, decided when the run started. */
  readonly stageTotal: number | null;
  readonly stageStartedAt: string | null;
}
