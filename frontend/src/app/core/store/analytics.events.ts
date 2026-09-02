import { type } from '@ngrx/signals';
import { eventGroup } from '@ngrx/signals/events';
import { AnalyticsView } from '@core/model/analytics';

export const analyticsEvents = eventGroup({
  source: 'Analytics',
  events: {
    opened: type<void>(),
    loaded: type<AnalyticsView>(),
    failed: type<string>(),
  },
});
