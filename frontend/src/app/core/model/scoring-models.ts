/** Mirrors `de.codeministry.leadgen.web.ScoringModels`. */
export interface ScoringModels {
  /**
   * Every model a run may be scored with, the default first. A list from the server
   * rather than a constant here, for the same reason nothing in this app names a weight,
   * a filter stage or a source type: a second copy disagrees with the configuration the
   * first time a model is added, and the symptom is a select offering something the
   * server refuses.
   */
  readonly available: readonly string[];
  /** What a run takes when nothing is chosen. Null when no judge is configured at all. */
  readonly preferred: string | null;
}
