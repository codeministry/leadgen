import { computed, inject } from '@angular/core';
import { signalStore, withComputed, withState } from '@ngrx/signals';
import { Events, on, withEventHandlers, withReducer } from '@ngrx/signals/events';
import { catchError, exhaustMap, map, of } from 'rxjs';
import { ShortlistApi } from '@core/api/shortlist.api';
import { ShortlistEntry } from '@core/model/shortlist-entry';
import { shortlistEvents } from './shortlist.events';

interface ShortlistState {
  entries: readonly ShortlistEntry[];
  error: string | null;
  loading: boolean;
}

const initialState: ShortlistState = { entries: [], error: null, loading: false };

export const ShortlistStore = signalStore(
  { providedIn: 'root' },
  withState(initialState),
  withComputed(({ entries }) => ({
    /** Every portal that appears, so the filter offers only what is actually there. */
    portals: computed(() =>
      [...new Set(entries().flatMap((entry) => entry.sources.map((s) => s.portal)))].sort(),
    ),
    unscored: computed(() => entries().filter((entry) => entry.score.value === null).length),
  })),
  withReducer(
    on(shortlistEvents.opened, () => ({ loading: true, error: null })),
    on(shortlistEvents.loaded, ({ payload }) => ({ entries: payload, loading: false })),
    on(shortlistEvents.failed, ({ payload }) => ({ error: payload, loading: false })),
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
    ];
  }),
);
