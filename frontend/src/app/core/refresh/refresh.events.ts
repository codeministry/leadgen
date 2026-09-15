import {type} from '@ngrx/signals';
import {eventGroup} from '@ngrx/signals/events';

/**
 * Why the screen is being asked to read its data again.
 *
 * Carried rather than dropped, because the two are not equally strong. A run that ended
 * rewrote the whole table — every score, every verdict, every package — while a tab coming
 * back to the front means only that time has passed and somebody may have changed something
 * elsewhere. A store that wants to treat them differently can; most do not.
 */
export type RefreshReason = 'run-ended' | 'tab-focused';

/**
 * One signal, two triggers, every store listening.
 *
 * Five of the eight stores already reloaded on `ingestEvents.finished`, and the machinery was
 * never the problem: that event fires only for a pass *this browser* started. A nightly
 * CronJob, another tab or a second machine left every screen showing what it had read once,
 * until somebody pressed reload — on the one screen whose subtitle is "what came in this
 * morning".
 */
export const refreshEvents = eventGroup({
  source: 'Refresh',
  events: {
    requested: type<RefreshReason>(),
  },
});
