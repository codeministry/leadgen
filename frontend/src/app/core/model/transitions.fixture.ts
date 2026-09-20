import {ApplicationStatus, TransitionMap} from '@core/model/application';

/**
 * The transition map as the server states it, for specs that open the board.
 *
 * Shared rather than written out twice: every screen that loads applications now loads this
 * with it, so a second copy would have to be found and corrected in both places the first
 * time the rule moves.
 */
const ALL: readonly ApplicationStatus[] = [
  'NEW',
  'SHORTLISTED',
  'PACKAGED',
  'SENT',
  'REPLIED',
  'INTERVIEW',
  'OFFER',
  'WON',
  'LOST',
  'REJECTED',
  'EXPIRED',
];

/** What NEW and SHORTLISTED may reach: each other, the package, and deciding against it. */
const BEFORE_PACKAGE: readonly ApplicationStatus[] = [
  'NEW',
  'SHORTLISTED',
  'PACKAGED',
  'REJECTED',
  'EXPIRED',
];

export const TRANSITIONS: TransitionMap = Object.fromEntries(
  ALL.map((status) => [
    status,
    status === 'NEW' || status === 'SHORTLISTED' ? BEFORE_PACKAGE : ALL,
  ]),
) as TransitionMap;
