import { computed, effect, inject, DOCUMENT } from '@angular/core';
import { signalStore, withComputed, withHooks, withState } from '@ngrx/signals';
import { Dispatcher, Events, on, withEventHandlers, withReducer } from '@ngrx/signals/events';
import { catchError, exhaustMap, map, of } from 'rxjs';
import { ConfigApi } from '@core/api/config.api';
import { scoringModelEvents } from './scoring-model.events';

const STORAGE_KEY = 'lg-scoring-model';

interface ScoringModelState {
  available: readonly string[];
  preferred: string | null;
  /** What was chosen here. Null is not "none": it is "whatever the server prefers". */
  chosen: string | null;
  error: string | null;
}

const initialState: ScoringModelState = {
  available: [],
  preferred: null,
  chosen: null,
  error: null,
};

/**
 * Which judge scores the next run.
 *
 * <b>Browser state, not server state.</b> The choice travels with the request that starts
 * a run and nothing about it is remembered on the other side, so a scheduled pass keeps
 * using the configured default no matter what this select last showed.
 *
 * Both kinds of I/O in one store, and each in the place this repo puts it: the list is
 * HTTP and sits in `withEventHandlers`, the choice is localStorage and sits in `withHooks`
 * plus an `effect`, exactly as in `core/theme/theme.store.ts`.
 */
export const ScoringModelStore = signalStore(
  { providedIn: 'root' },
  withState(initialState),
  withComputed(({ available, preferred, chosen }) => ({
    /**
     * What the next run will actually ask for. The select shows this rather than `chosen`,
     * so the row is never blank while the list is still loading.
     */
    effective: computed(() => chosen() ?? preferred()),
    /**
     * One model is not a choice, and no model at all means nothing is configured to judge.
     * Either way there is nothing to pick, so the select is not shown.
     */
    selectable: computed(() => available().length > 1),
  })),
  withReducer(
    on(scoringModelEvents.opened, () => ({ error: null })),
    on(scoringModelEvents.loaded, ({ payload }, state) => ({
      available: payload.available,
      preferred: payload.preferred,
      // A name the server no longer offers is dropped rather than sent. The server refuses
      // it anyway — it is an allowlist in front of a billed endpoint — but a choice made
      // weeks ago and kept in localStorage would otherwise turn the next click into a 400
      // for a reason nobody can see.
      chosen: state.chosen && payload.available.includes(state.chosen) ? state.chosen : null,
    })),
    on(scoringModelEvents.failed, ({ payload }) => ({ error: payload })),
    on(scoringModelEvents.restored, ({ payload }) => ({ chosen: payload })),
    on(scoringModelEvents.chosen, ({ payload }) => ({ chosen: payload })),
  ),
  withEventHandlers(() => {
    const events = inject(Events);
    const api = inject(ConfigApi);

    return [
      events.on(scoringModelEvents.opened).pipe(
        exhaustMap(() =>
          api.scoringModels().pipe(
            map((models) => scoringModelEvents.loaded(models)),
            catchError(() => of(scoringModelEvents.failed('error.scoringModelsLoad'))),
          ),
        ),
      ),
    ];
  }),
  withHooks({
    onInit(store) {
      const view = inject(DOCUMENT).defaultView;
      const dispatcher = inject(Dispatcher);

      dispatcher.dispatch(scoringModelEvents.restored(read(view)));
      dispatcher.dispatch(scoringModelEvents.opened());

      effect(() => write(view, store.chosen()));
    },
  }),
);

/** Storage throws in private mode, and a model choice is not worth failing the boot over. */
function read(view: Window | null): string | null {
  try {
    return view?.localStorage.getItem(STORAGE_KEY) ?? null;
  } catch {
    return null;
  }
}

function write(view: Window | null, chosen: string | null): void {
  try {
    if (chosen === null) {
      view?.localStorage.removeItem(STORAGE_KEY);
    } else {
      view?.localStorage.setItem(STORAGE_KEY, chosen);
    }
  } catch {
    // Nothing to do: the choice simply will not survive the reload.
  }
}
