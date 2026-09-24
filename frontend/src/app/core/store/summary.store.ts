import {inject} from '@angular/core';
import {signalStore, withState} from '@ngrx/signals';
import {Events, on, withEventHandlers, withReducer} from '@ngrx/signals/events';
import {catchError, exhaustMap, map, of} from 'rxjs';
import {AnalyticsApi} from '@core/api/analytics.api';
import {AnalyticsSummary} from '@core/model/analytics';
import {refreshEvents} from '@core/refresh/refresh.events';
import {summaryEvents} from './summary.events';

interface SummaryState {
    summary: AnalyticsSummary | null;
    error: string | null;
    loading: boolean;
}

const initialState: SummaryState = {summary: null, error: null, loading: false};

/**
 * The dashboard's small cells: fourteen days of intake, the score bands, the last run's
 * health. One read, re-read whenever anything says the data moved, the same shape as
 * `StatusStore`. Separate from `AnalyticsStore` on purpose: that one carries the analytics
 * screen's whole payload, and the dashboard must not pay for it (spec 006, ISC-266).
 */
export const SummaryStore = signalStore(
    {providedIn: 'root'},
    withState(initialState),
    withReducer(
        on(summaryEvents.opened, () => ({loading: true, error: null})),
        on(summaryEvents.loaded, ({payload}) => ({summary: payload, loading: false})),
        on(summaryEvents.failed, ({payload}) => ({error: payload, loading: false})),
    ),
    withEventHandlers(() => {
        const events = inject(Events);
        const api = inject(AnalyticsApi);
        return [
            events.on(refreshEvents.requested).pipe(map(() => summaryEvents.opened())),
            events.on(summaryEvents.opened).pipe(
                exhaustMap(() =>
                    api.summary().pipe(
                        map((summary) => summaryEvents.loaded(summary)),
                        catchError(() => of(summaryEvents.failed('error.summaryLoad'))),
                    ),
                ),
            ),
        ];
    }),
);
