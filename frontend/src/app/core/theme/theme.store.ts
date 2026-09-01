import { DOCUMENT, computed, effect, inject } from '@angular/core';
import { signalStore, withComputed, withHooks, withState } from '@ngrx/signals';
import { Dispatcher, on, withReducer } from '@ngrx/signals/events';
import { themeEvents } from './theme.events';
import {
  DATA_THEME_ATTR,
  ResolvedTheme,
  THEME_STORAGE_KEY,
  ThemePreference,
  isThemePreference,
} from './theme.model';

interface ThemeState {
  preference: ThemePreference;
  systemPrefersDark: boolean;
}

const initialState: ThemeState = { preference: 'system', systemPrefersDark: false };

/**
 * Same triplet as `core/store/status.store.ts`: a `*.events.ts` beside a
 * `*.store.ts`, with `withReducer` for the transitions. The I/O differs — it is
 * the DOM and localStorage rather than HTTP — so it sits in `withHooks` and an
 * `effect` instead of `withEventHandlers`: the write follows the *resolved*
 * theme, which is a computed signal rather than an event.
 *
 * `system` is the absence of `data-theme`, because daisyUI emits lg-dark under
 * `:root:not([data-theme])` inside a prefers-color-scheme query. Removing the
 * attribute is the entire implementation of "follow the operating system".
 */
export const ThemeStore = signalStore(
  { providedIn: 'root' },
  withState(initialState),
  withComputed(({ preference, systemPrefersDark }) => ({
    theme: computed<ResolvedTheme>(() => {
      const chosen = preference();
      if (chosen === 'system') {
        return systemPrefersDark() ? 'lg-dark' : 'lg-light';
      }
      return chosen === 'dark' ? 'lg-dark' : 'lg-light';
    }),
  })),
  withReducer(
    on(themeEvents.restored, ({ payload }) => ({ preference: payload })),
    on(themeEvents.chosen, ({ payload }) => ({ preference: payload })),
    on(themeEvents.systemChanged, ({ payload }) => ({ systemPrefersDark: payload })),
  ),
  withHooks({
    onInit(store) {
      const document = inject(DOCUMENT);
      const dispatcher = inject(Dispatcher);
      const view = document.defaultView;
      // Guarded rather than assumed: `matchMedia` is absent in the test
      // environment and on a server, and a missing colour scheme must not be
      // able to fail the boot. Same reasoning as the storage try/catch below.
      const media =
        typeof view?.matchMedia === 'function'
          ? view.matchMedia('(prefers-color-scheme: dark)')
          : null;

      dispatcher.dispatch(themeEvents.systemChanged(media?.matches ?? false));
      dispatcher.dispatch(themeEvents.restored(readPreference(view)));

      media?.addEventListener('change', (change) => {
        dispatcher.dispatch(themeEvents.systemChanged(change.matches));
      });

      effect(() => {
        const preference = store.preference();
        const root = document.documentElement;

        // The inline script in index.html already did this once before first
        // paint. This keeps it true for every change after that.
        if (preference === 'system') {
          root.removeAttribute(DATA_THEME_ATTR);
        } else {
          root.setAttribute(DATA_THEME_ATTR, store.theme());
        }

        writePreference(view, preference);
      });
    },
  }),
);

/** Storage throws in private mode. A colour scheme is not worth failing the boot over. */
function readPreference(view: Window | null): ThemePreference {
  try {
    const stored = view?.localStorage.getItem(THEME_STORAGE_KEY) ?? null;
    return isThemePreference(stored) ? stored : 'system';
  } catch {
    return 'system';
  }
}

function writePreference(view: Window | null, preference: ThemePreference): void {
  try {
    view?.localStorage.setItem(THEME_STORAGE_KEY, preference);
  } catch {
    // Nothing to do: the choice simply will not survive the reload.
  }
}
