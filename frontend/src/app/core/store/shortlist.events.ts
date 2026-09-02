import { type } from '@ngrx/signals';
import { eventGroup } from '@ngrx/signals/events';
import { FunnelView } from '@core/model/funnel';
import { ShortlistEntry } from '@core/model/shortlist-entry';
import { ShortlistFilters, ShortlistPage } from '@core/model/shortlist-page';

export const shortlistEvents = eventGroup({
  source: 'Shortlist',
  events: {
    /** The filters changed, or the screen was opened. Either way it is a first page. */
    opened: type<ShortlistFilters>(),
    loaded: type<ShortlistPage>(),
    /** The reader reached the end of what is loaded. */
    moreRequested: type<void>(),
    moreLoaded: type<ShortlistPage>(),
    failed: type<string>(),
    /** One offer by id, for the detail — which also has to open a rejected one. */
    offerRequested: type<number>(),
    offerLoaded: type<ShortlistEntry>(),
    offerFailed: type<string>(),
    funnelOpened: type<void>(),
    funnelLoaded: type<FunnelView>(),
    /**
     * The deliberate exception to the staleness guard: a run judges only what changed, and
     * this is how one offer is judged again anyway. It costs a call, so it is a request
     * somebody makes rather than something a screen does on init.
     */
    rescoreRequested: type<number>(),
    rescored: type<ShortlistEntry>(),
    rescoreFailed: type<string>(),
    /**
     * Off the working list, or back onto it. The one thing about an offer a person owns —
     * everything else here is written by a run and rewritten by the next one.
     */
    archiveRequested: type<{ id: number; archived: boolean }>(),
    archived: type<ShortlistEntry>(),
    archiveFailed: type<string>(),
  },
});
