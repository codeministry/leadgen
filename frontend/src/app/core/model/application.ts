/** The state machine from CONCEPT §5. It lives in configuration, not in an enum. */
export type ApplicationStatus =
  | 'NEW'
  | 'SHORTLISTED'
  | 'PACKAGED'
  | 'SENT'
  | 'REPLIED'
  | 'INTERVIEW'
  | 'OFFER'
  | 'WON'
  | 'LOST'
  | 'REJECTED'
  | 'EXPIRED';

export type PipelineLaneId = 'backlog' | 'prepared' | 'out' | 'talking' | 'closed';

export interface PipelineLane {
  readonly id: PipelineLaneId;
  readonly label: string;
  readonly states: readonly ApplicationStatus[];
}

/**
 * Eleven states are too many columns to read at a glance, so the board groups
 * them into five lanes and keeps the exact state on the card.
 */
export const PIPELINE_LANES: readonly PipelineLane[] = [
  { id: 'backlog', label: 'Backlog', states: ['NEW', 'SHORTLISTED'] },
  { id: 'prepared', label: 'Prepared', states: ['PACKAGED'] },
  { id: 'out', label: 'Out', states: ['SENT', 'REPLIED'] },
  { id: 'talking', label: 'Talking', states: ['INTERVIEW', 'OFFER'] },
  { id: 'closed', label: 'Closed', states: ['WON', 'LOST', 'REJECTED', 'EXPIRED'] },
];

export interface Application {
  readonly id: string;
  readonly offerId: string;
  readonly title: string;
  readonly agency: string | null;
  readonly status: ApplicationStatus;
  readonly scoreValue: number | null;
  readonly rateEur: number | null;
  readonly sentOn: string | null;
  readonly followUpOn: string | null;
}
