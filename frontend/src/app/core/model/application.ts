/**
 * Mirrors `de.codeministry.leadgen.application.ApplicationStatus`.
 *
 * The transitions are documented, not enforced: every value here is entered by hand
 * about events the system never saw, and a tool that refuses a correction because the
 * path looks wrong is a tool nobody keeps current.
 */
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

/**
 * A lane of the board, as `GET /api/applications/lanes` states it.
 *
 * The grouping is not repeated here. Eleven states across five lanes is a decision the
 * enum already makes, and a second copy in the browser would disagree with it the first
 * time a state is added — visibly on the board, invisibly in the code.
 */
export interface PipelineLane {
  readonly id: string;
  readonly label: string;
  readonly states: readonly ApplicationStatus[];
}

/** Mirrors `ApplicationView`: the application plus enough of the offer to recognise it. */
export interface ApplicationView {
  readonly id: number;
  readonly offerId: number;
  readonly status: ApplicationStatus;
  readonly title: string;
  readonly agency: string | null;
  readonly portal: string | null;
  readonly url: string | null;
  readonly scoreValue: number | null;
  readonly rateEur: number | null;
  readonly packageDir: string | null;
  readonly sentOn: string | null;
  readonly followUpOn: string | null;
  /** Computed on the server, because "due" depends on its idea of today, not the browser's. */
  readonly followUpDue: boolean;
  readonly outcome: string | null;
  readonly note: string | null;
  readonly updatedAt: string;
}

/**
 * What a PATCH says. Only the status is required, because a correction usually changes
 * one thing.
 *
 * `clearFollowUp` is the difference between "leave the follow-up alone" and "remove it".
 * A null date cannot say both, and a board where a reminder can be set but never
 * cancelled fills up with dead reminders.
 */
export interface ApplicationUpdate {
  readonly status: ApplicationStatus;
  readonly sentOn?: string | null;
  readonly followUpOn?: string | null;
  readonly clearFollowUp?: boolean;
  readonly outcome?: string | null;
  readonly note?: string | null;
}

/** One recorded change. The history a single mutable row cannot answer for. */
export interface ApplicationEvent {
  readonly fromStatus: ApplicationStatus | null;
  readonly toStatus: ApplicationStatus;
  readonly note: string | null;
  readonly recordedAt: string;
}

/** `SHORTLISTED` on the wire, "Shortlisted" in a select. */
export function statusLabel(status: ApplicationStatus): string {
  return status.charAt(0) + status.slice(1).toLowerCase();
}
