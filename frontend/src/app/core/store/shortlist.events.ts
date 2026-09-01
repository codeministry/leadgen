import { type } from '@ngrx/signals';
import { eventGroup } from '@ngrx/signals/events';
import { ShortlistEntry } from '@core/model/shortlist-entry';

export const shortlistEvents = eventGroup({
  source: 'Shortlist',
  events: {
    opened: type<void>(),
    loaded: type<readonly ShortlistEntry[]>(),
    failed: type<string>(),
  },
});
