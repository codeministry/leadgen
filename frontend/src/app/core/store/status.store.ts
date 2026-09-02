import { inject } from '@angular/core';
import { signalStore, withState } from '@ngrx/signals';
import { Events, on, withEventHandlers, withReducer } from '@ngrx/signals/events';
import { catchError, exhaustMap, map, of } from 'rxjs';
import { AppStatus, StatusApi } from '@core/api/status.api';
import { statusEvents } from './status.events';

interface StatusState {
  status: AppStatus | null;
  error: string | null;
  loading: boolean;
}

const initialState: StatusState = { status: null, error: null, loading: false };

/**
 * The model store for this repo: a triplet of `*.store.ts` + `*.events.ts`, with
 * `withReducer` for the state transitions and `withEventHandlers` for the I/O.
 * It exists mainly so the skeleton proves the full path — component to proxy to
 * Spring and back — rather than only that each half compiles.
 */
export const StatusStore = signalStore(
  { providedIn: 'root' },
  withState(initialState),
  withReducer(
    on(statusEvents.opened, () => ({ loading: true, error: null })),
    on(statusEvents.loaded, ({ payload }) => ({ status: payload, loading: false })),
    on(statusEvents.failed, ({ payload }) => ({ error: payload, loading: false })),
  ),
  withEventHandlers(() => {
    const events = inject(Events);
    const api = inject(StatusApi);

    return [
      events.on(statusEvents.opened).pipe(
        exhaustMap(() =>
          api.load().pipe(
            map((status) => statusEvents.loaded(status)),
            catchError(() => of(statusEvents.failed('error.statusLoad'))),
          ),
        ),
      ),
    ];
  }),
);
