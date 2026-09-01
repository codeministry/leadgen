/**
 * One stage of the hard filter.
 *
 * The id is a plain string and not a union of the seven stage names. The stages are the
 * `FilterStage` enum on the server and they arrive with the counts; a second list of them
 * here would disagree with it the first time a stage is added, and the symptom would be a
 * type error in a component that has no business knowing the filter at all.
 */
export interface FunnelStage {
  readonly id: string;
  readonly label: string;
  /** How many offers this stage removed. Counts come from the run, not from here. */
  readonly removed: number;
}
