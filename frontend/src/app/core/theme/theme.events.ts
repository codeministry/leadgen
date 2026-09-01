import { type } from '@ngrx/signals';
import { eventGroup } from '@ngrx/signals/events';
import { ThemePreference } from './theme.model';

export const themeEvents = eventGroup({
  source: 'Theme',
  events: {
    /** The stored preference, read back at startup. */
    restored: type<ThemePreference>(),
    /** The reader picked one of the three states. */
    chosen: type<ThemePreference>(),
    /** The operating system flipped while the app was open. */
    systemChanged: type<boolean>(),
  },
});
