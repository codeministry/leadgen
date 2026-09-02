import { type } from '@ngrx/signals';
import { eventGroup } from '@ngrx/signals/events';
import { AppStatus } from '@core/api/status.api';

/**
 * The status probe. It exists to prove the whole path — component, proxy, Spring, Postgres —
 * rather than to show anybody a version number, which is why it is a store at all.
 */
export const statusEvents = eventGroup({
  source: 'Status',
  events: {
    opened: type<void>(),
    loaded: type<AppStatus>(),
    failed: type<string>(),
  },
});
