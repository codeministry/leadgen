import { type } from '@ngrx/signals';
import { eventGroup } from '@ngrx/signals/events';
import { LanguagePreference, Language } from './language.model';

export const languageEvents = eventGroup({
  source: 'Language',
  events: {
    /** The stored preference, read back at startup. */
    restored: type<LanguagePreference>(),
    /** The reader picked one of the three states. */
    chosen: type<LanguagePreference>(),
    /** What the browser says it wants, read once — it cannot change while the tab lives. */
    systemDetected: type<Language>(),
  },
});
