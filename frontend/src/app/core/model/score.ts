/**
 * A number without a reason gets ignored within a week, so a score always
 * carries the factors that produced it.
 */
export interface ScoreReason {
  /** A key from `scoring.weights` or `scoring.penalties` in matching-rules.yaml. */
  readonly factor: string;
  readonly label: string;
  readonly points: number;
    /**
     * What this factor could have contributed, which is what makes the total a share and
     * not a sum. Zero for an absolute bonus or penalty, and for a score written before the
     * column existed — in both cases there is no denominator to show.
     */
    readonly maxPoints: number;
}

export interface Score {
  /** Null when the pipeline ran without a language model. The shortlist still exists. */
  readonly value: number | null;
  readonly hardPass: boolean;
  readonly reasons: readonly ScoreReason[];
  readonly model: string | null;
  readonly rulesetVersion: string | null;
}
