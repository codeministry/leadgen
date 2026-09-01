import { type } from '@ngrx/signals';
import { eventGroup } from '@ngrx/signals/events';
import { AppStatus } from '@core/api/status.api';

export const statusEvents = eventGroup({
  source: 'Status',
  events: {
    opened: type<void>(),
    loaded: type<AppStatus>(),
    failed: type<string>(),
  },
});
