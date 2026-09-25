import {DOCUMENT, effect, inject} from '@angular/core';
import {signalStore, withHooks, withState} from '@ngrx/signals';
import {Dispatcher, on, withReducer} from '@ngrx/signals/events';
import {withAppDevtools} from '@core/store/devtools';
import {densityEvents} from './density.events';
import {DEFAULT_DENSITY, DENSITY_STORAGE_KEY, isListDensity, ListDensity} from './density.model';

interface DensityState {
  density: ListDensity;
}

/**
 * The theme store's pattern, one state smaller: a `*.events.ts` beside this file,
 * `withReducer` for the transitions, and the I/O — localStorage — in `withHooks` and an
 * `effect` rather than `withEventHandlers`. The card never reads this store; the page does
 * and hands the density down as an input, so the card stays a function of its inputs.
 */
export const DensityStore = signalStore(
  {providedIn: 'root'},
  withState<DensityState>({density: DEFAULT_DENSITY}),
  withAppDevtools('density'),
  withReducer(
    on(densityEvents.restored, ({payload}) => ({density: payload})),
    on(densityEvents.chosen, ({payload}) => ({density: payload})),
  ),
  withHooks({
    onInit(store) {
      const view = inject(DOCUMENT).defaultView;
      inject(Dispatcher).dispatch(densityEvents.restored(readDensity(view)));

      effect(() => writeDensity(view, store.density()));
    },
  }),
);

/** Storage throws in private mode. A list layout is not worth failing the boot over. */
function readDensity(view: Window | null): ListDensity {
  try {
    const stored = view?.localStorage.getItem(DENSITY_STORAGE_KEY) ?? null;
    return isListDensity(stored) ? stored : DEFAULT_DENSITY;
  } catch {
    return DEFAULT_DENSITY;
  }
}

function writeDensity(view: Window | null, density: ListDensity): void {
  try {
    view?.localStorage.setItem(DENSITY_STORAGE_KEY, density);
  } catch {
    // Nothing to do: the choice simply will not survive the reload.
  }
}
