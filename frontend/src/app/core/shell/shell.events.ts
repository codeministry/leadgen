import { type } from '@ngrx/signals';
import { eventGroup } from '@ngrx/signals/events';

export const shellEvents = eventGroup({
  source: 'Shell',
  events: {
    /** The stored rail state, read back at startup. */
    restored: type<boolean>(),
    /** The reader collapsed or expanded the navigation rail. */
    railToggled: type<void>(),
  },
});
