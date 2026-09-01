import { type } from '@ngrx/signals';
import { eventGroup } from '@ngrx/signals/events';
import { FunnelView } from '@core/model/funnel';
import { ShortlistEntry } from '@core/model/shortlist-entry';

export const shortlistEvents = eventGroup({
  source: 'Shortlist',
  events: {
    opened: type<void>(),
    loaded: type<readonly ShortlistEntry[]>(),
    failed: type<string>(),
    /** One offer by id, for the detail — which also has to open a rejected one. */
    offerRequested: type<number>(),
    offerLoaded: type<ShortlistEntry>(),
    offerFailed: type<string>(),
    funnelOpened: type<void>(),
    funnelLoaded: type<FunnelView>(),
  },
});
