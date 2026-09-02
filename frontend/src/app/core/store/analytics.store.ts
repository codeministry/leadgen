import { inject } from '@angular/core';
import { signalStore, withState } from '@ngrx/signals';
import { Events, on, withEventHandlers, withReducer } from '@ngrx/signals/events';
import { catchError, exhaustMap, map, of } from 'rxjs';
import { AnalyticsApi } from '@core/api/analytics.api';
import { AnalyticsView } from '@core/model/analytics';
import { analyticsEvents } from './analytics.events';
import { ingestEvents } from './ingest.events';

interface AnalyticsState {
  view: AnalyticsView | null;
  error: string | null;
  loading: boolean;
}

const initialState: AnalyticsState = { view: null, error: null, loading: false };

/**
 * `GET /api/analytics` — one payload, one loading state, one failure.
 *
 * <p>`exhaustMap` rather than `switchMap`: the request is idempotent and the answer is the
 * same whichever of two in-flight ones wins, so a second open while the first is running is
 * work nobody asked for.
 */
export const AnalyticsStore = signalStore(
  { providedIn: 'root' },
  withState(initialState),
  withReducer(
    on(analyticsEvents.opened, () => ({ loading: true, error: null })),
    on(analyticsEvents.loaded, ({ payload }) => ({ view: payload, loading: false })),
    on(analyticsEvents.failed, ({ payload }) => ({ error: payload, loading: false })),
  ),
  withEventHandlers(() => {
    const events = inject(Events);
    const api = inject(AnalyticsApi);

    return [
      events.on(analyticsEvents.opened).pipe(
        exhaustMap(() =>
          api.load().pipe(
            map((view) => analyticsEvents.loaded(view)),
            catchError(() => of(analyticsEvents.failed('error.analyticsLoad'))),
          ),
        ),
      ),
      // A run rewrites every number on this screen. Expressed as this store's own load
      // event, so there is one path that fetches and `ingest` knows nothing about who
      // listens — the same wiring the shortlist, the board and the sources screen use.
      events.on(ingestEvents.finished).pipe(map(() => analyticsEvents.opened())),
    ];
  }),
);
