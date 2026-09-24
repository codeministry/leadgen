import {type} from '@ngrx/signals';
import {eventGroup} from '@ngrx/signals/events';
import {AnalyticsSummary} from '@core/model/analytics';

/** What the dashboard's control room asks for, and the two answers it can get. */
export const summaryEvents = eventGroup({
    source: 'Summary',
    events: {
        opened: type<void>(),
        loaded: type<AnalyticsSummary>(),
        failed: type<string>(),
    },
});
