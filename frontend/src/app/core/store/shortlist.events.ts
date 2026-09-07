import {type} from '@ngrx/signals';
import {eventGroup} from '@ngrx/signals/events';
import {FunnelView} from '@core/model/funnel';
import {ShortlistEntry} from '@core/model/shortlist-entry';
import {ShortlistFilters, ShortlistPage} from '@core/model/shortlist-page';

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
    /**
     * Every event above is about one offer. These are not, and the reason is that clearing
     * the working list is the one thing done in bulk — reading an advert never is.
     */
    offerPicked: type<{ id: number; picked: boolean }>(),
    /**
     * A Shift-click. The page resolves the range against `entries` and sends the ids,
     * because the list *is* the range and the store must not hold a second copy of its
     * order.
     */
    rangePicked: type<readonly number[]>(),
    picksCleared: type<void>(),
    /**
     * Carries the ids rather than reading them off the store: they are what the dialog
     * named a count for, and a reducer that has already run must not be able to change
     * the answer between the confirmation and the request.
     */
    bulkArchiveRequested: type<readonly number[]>(),
    /**
     * The ids asked for, plus what the server actually wrote. Both are needed: the ids say
     * which rows to drop, the counts say how far to move the sentence beside the list.
     */
    bulkArchived: type<{ ids: readonly number[]; archived: number; unscored: number }>(),
    bulkArchiveFailed: type<string>(),
  },
});
