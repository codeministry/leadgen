/**
 * The hard filter as a shape. Mirrors `de.codeministry.leadgen.offer.FunnelView`.
 *
 * The stages arrive in the order they run, and that order is the meaning: an offer stops
 * at the first stage that rejects it, which is the only reason the counts sum to the
 * total. `survived` is stated rather than derived, so nothing has to reproduce the
 * subtraction and get it subtly wrong.
 *
 * The whole shape describes the working list. Archived offers are outside every number in
 * it, on both sides of the subtraction, and `archived` sits beside the shape rather than
 * inside it: leaving the working list is not something the filter did.
 */
export interface FunnelStageCount {
  readonly id: string;
  readonly label: string;
  readonly removed: number;
}

export interface FunnelView {
  readonly total: number;
  readonly stages: readonly FunnelStageCount[];
  readonly survived: number;
  /** Primaries taken off the working list, by age or by hand. Not a stage. */
  readonly archived: number;
}
