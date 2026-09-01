/** The hard filter's stages, in the order `docs/samples/simulate_filter.py` applies them. */
export type FunnelStageId =
  | 'abroad'
  | 'remote-share'
  | 'distance'
  | 'stack-role'
  | 'core-skill';

export interface FunnelStage {
  readonly id: FunnelStageId;
  readonly label: string;
  /** How many offers this stage removed. Counts come from the run, not from here. */
  readonly removed: number;
}
