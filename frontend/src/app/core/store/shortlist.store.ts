import { computed, inject } from '@angular/core';
import { signalStore, withComputed, withState } from '@ngrx/signals';
import { Events, on, withEventHandlers, withReducer } from '@ngrx/signals/events';
import { catchError, exhaustMap, map, of, switchMap } from 'rxjs';
import { ShortlistApi } from '@core/api/shortlist.api';
import { ingestEvents } from './ingest.events';
import { FunnelView } from '@core/model/funnel';
import { ShortlistEntry } from '@core/model/shortlist-entry';
import { shortlistEvents } from './shortlist.events';

interface ShortlistState {
  entries: readonly ShortlistEntry[];
  /** The offer the detail is showing, fetched by id rather than found in the list. */
  selected: ShortlistEntry | null;
  funnel: FunnelView | null;
  error: string | null;
  loading: boolean;
}

const initialState: ShortlistState = {
  entries: [],
  selected: null,
  funnel: null,
  error: null,
  loading: false,
};

export const ShortlistStore = signalStore(
  { providedIn: 'root' },
  withState(initialState),
  withComputed(({ entries }) => ({
    /** Every portal that appears, so the filter offers only what is actually there. */
    portals: computed(() =>
      [
        ...new Set(
          entries()
            .flatMap((entry) => entry.sources.map((source) => source.portal))
            .filter((portal): portal is string => portal !== null),
        ),
      ].sort(),
    ),
    unscored: computed(() => entries().filter((entry) => entry.score.value === null).length),
  })),
  withReducer(
    on(shortlistEvents.opened, () => ({ loading: true, error: null })),
    on(shortlistEvents.loaded, ({ payload }) => ({ entries: payload, loading: false })),
    on(shortlistEvents.failed, ({ payload }) => ({ error: payload, loading: false })),
    // Cleared on request, not on arrival: leaving the previous offer on screen while the
    // next one loads shows the wrong ad under the right title.
    on(shortlistEvents.offerRequested, () => ({ selected: null, loading: true, error: null })),
    on(shortlistEvents.offerLoaded, ({ payload }) => ({ selected: payload, loading: false })),
    on(shortlistEvents.offerFailed, ({ payload }) => ({ error: payload, loading: false })),
    on(shortlistEvents.funnelLoaded, ({ payload }) => ({ funnel: payload })),
  ),
  withEventHandlers(() => {
    const events = inject(Events);
    const api = inject(ShortlistApi);

    return [
      events.on(shortlistEvents.opened).pipe(
        exhaustMap(() =>
          api.load().pipe(
            map((entries) => shortlistEvents.loaded(entries)),
            catchError(() => of(shortlistEvents.failed('The shortlist did not load.'))),
          ),
        ),
      ),
      events.on(shortlistEvents.funnelOpened).pipe(
        exhaustMap(() =>
          api.funnel().pipe(
            map((funnel) => shortlistEvents.funnelLoaded(funnel)),
            catchError(() => of(shortlistEvents.failed('The filter counts did not load.'))),
          ),
        ),
      ),
      // A run rewrites everything this store shows, and the screens load once on init:
      // without this the shortlist a person is looking at while the run finishes is the
      // one from before it. The reload is expressed as this store's own load event, so
      // there is one path that fetches and `ingest` knows nothing about who listens.
      events.on(ingestEvents.finished).pipe(map(() => shortlistEvents.opened())),
      events.on(ingestEvents.finished).pipe(map(() => shortlistEvents.funnelOpened())),
      // Switched, not exhausted: clicking through two offers quickly must end on the
      // second one, and the first answer is then worth nothing.
      events.on(shortlistEvents.offerRequested).pipe(
        switchMap(({ payload }) =>
          api.one(payload).pipe(
            map((entry) => shortlistEvents.offerLoaded(entry)),
            catchError(() => of(shortlistEvents.offerFailed('That offer did not load.'))),
          ),
        ),
      ),
    ];
  }),
);
