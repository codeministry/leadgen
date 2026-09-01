import { DOCUMENT, effect, inject } from '@angular/core';
import { signalStore, withHooks, withState } from '@ngrx/signals';
import { Dispatcher, on, withReducer } from '@ngrx/signals/events';
import { shellEvents } from './shell.events';

interface ShellState {
  railOpen: boolean;
}

const RAIL_STORAGE_KEY = 'lg-rail-open';

const initialState: ShellState = { railOpen: true };

/**
 * The navigation rail's open state. It lives in a store rather than in the shell
 * component for one reason: it is persisted, and every other persisted preference
 * in this app is a store. One place to look when a preference misbehaves beats a
 * component that quietly writes to localStorage on the side.
 */
export const ShellStore = signalStore(
  { providedIn: 'root' },
  withState(initialState),
  withReducer(
    on(shellEvents.restored, ({ payload }) => ({ railOpen: payload })),
    on(shellEvents.railToggled, (_, state) => ({ railOpen: !state.railOpen })),
  ),
  withHooks({
    onInit(store) {
      const view = inject(DOCUMENT).defaultView;
      const dispatcher = inject(Dispatcher);

      dispatcher.dispatch(shellEvents.restored(read(view)));

      effect(() => {
        const open = store.railOpen();
        try {
          view?.localStorage.setItem(RAIL_STORAGE_KEY, open ? 'open' : 'closed');
        } catch {
          // Private mode. The rail simply reopens on the next visit.
        }
      });
    },
  }),
);

function read(view: Window | null): boolean {
  try {
    return view?.localStorage.getItem(RAIL_STORAGE_KEY) !== 'closed';
  } catch {
    return true;
  }
}
