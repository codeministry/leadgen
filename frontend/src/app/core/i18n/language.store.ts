import { DOCUMENT, computed, effect, inject } from '@angular/core';
import { TranslocoService } from '@jsverse/transloco';
import { signalStore, withComputed, withHooks, withState } from '@ngrx/signals';
import { Dispatcher, on, withReducer } from '@ngrx/signals/events';
import { languageEvents } from './language.events';
import {
  FALLBACK_LANGUAGE,
  LANGUAGE_STORAGE_KEY,
  Language,
  LanguagePreference,
  isLanguagePreference,
  languageOf,
} from './language.model';

interface LanguageState {
  preference: LanguagePreference;
  systemLanguage: Language;
}

const initialState: LanguageState = {
  preference: 'system',
  systemLanguage: FALLBACK_LANGUAGE,
};

/**
 * The same shape as `core/theme/theme.store.ts`, and for the same reason: the I/O here is
 * the DOM, localStorage and Transloco rather than HTTP, so it lives in `withHooks` and an
 * `effect` on the *resolved* language instead of in `withEventHandlers`.
 *
 * <p>Transloco holds the active language itself, so this store could have been left out
 * entirely. It exists because the preference and the active language are two different
 * things: `system` is a preference and resolves to whatever the browser asks for, and only
 * a store that keeps the three states apart can render a toggle that still shows `system`
 * as chosen while English is on screen.
 */
export const LanguageStore = signalStore(
  { providedIn: 'root' },
  withState(initialState),
  withComputed(({ preference, systemLanguage }) => ({
    language: computed<Language>(() => {
      const chosen = preference();
      return chosen === 'system' ? systemLanguage() : chosen;
    }),
  })),
  withReducer(
    on(languageEvents.restored, ({ payload }) => ({ preference: payload })),
    on(languageEvents.chosen, ({ payload }) => ({ preference: payload })),
    on(languageEvents.systemDetected, ({ payload }) => ({ systemLanguage: payload })),
  ),
  withHooks({
    onInit(store) {
      const document = inject(DOCUMENT);
      const transloco = inject(TranslocoService);
      const dispatcher = inject(Dispatcher);
      const view = document.defaultView;

      dispatcher.dispatch(
        languageEvents.systemDetected(languageOf(view?.navigator.language) ?? FALLBACK_LANGUAGE),
      );
      dispatcher.dispatch(languageEvents.restored(readPreference(view)));

      effect(() => {
        const language = store.language();

        transloco.setActiveLang(language);
        // Not cosmetic: it is what a screen reader picks a voice from and what the
        // browser hyphenates by, and it is wrong on every page until something sets it.
        document.documentElement.lang = language;

        writePreference(view, store.preference());
      });
    },
  }),
);

/** Storage throws in private mode. A language is not worth failing the boot over. */
function readPreference(view: Window | null): LanguagePreference {
  try {
    const stored = view?.localStorage.getItem(LANGUAGE_STORAGE_KEY) ?? null;
    return isLanguagePreference(stored) ? stored : 'system';
  } catch {
    return 'system';
  }
}

function writePreference(view: Window | null, preference: LanguagePreference): void {
  try {
    view?.localStorage.setItem(LANGUAGE_STORAGE_KEY, preference);
  } catch {
    // Nothing to do: the choice simply will not survive the reload.
  }
}
