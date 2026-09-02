import { type } from '@ngrx/signals';
import { eventGroup } from '@ngrx/signals/events';
import { AnalyticsView } from '@core/model/analytics';

/** What the analytics screen can ask for, and the two answers it can get. */
export const analyticsEvents = eventGroup({
  source: 'Analytics',
  events: {
    opened: type<void>(),
    loaded: type<AnalyticsView>(),
    failed: type<string>(),
  },
});
