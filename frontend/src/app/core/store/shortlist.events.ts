import {type} from '@ngrx/signals';
import {eventGroup} from '@ngrx/signals/events';
import {FunnelView} from '@core/model/funnel';
import {ShortlistEntry} from '@core/model/shortlist-entry';
import {ShortlistFilters, ShortlistPage} from '@core/model/shortlist-page';

/**
 * A single archive or restore the server refused, tied to the offer it was about. `inline`: the
 * write came from the row (a swipe) or from the archive toast, so the refusal belongs in that row
 * while it is on screen; otherwise it belongs to the detail, and only while the detail shows that
 * offer.
 */
export interface ArchiveRefusal {
  readonly id: number;
  readonly message: string;
  readonly inline: boolean;
}

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
    /**
     * Something happened that this list has not read. A flag rather than a reload, because
     * re-reading a keyset-paged list empties `entries` and starts again at page one — a
     * reader forty offers down would be returned to the top for news they did not ask about.
     */
    wentStale: type<void>(),
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
     * The original ad, asked for again past a failure the run remembered. `fetched` carries
     * the entry whether the page answered or refused once more — a refusal is a recorded
     * outcome with its note, not a failure of the request. `fetchFailed` is the request itself
     * turned away: an offer that is not one to fetch, or a minute already spent.
     */
    fetchRequested: type<number>(),
    fetched: type<ShortlistEntry>(),
    fetchFailed: type<string>(),
    /**
     * Off the working list, or back onto it. The one thing about an offer a person owns —
     * everything else here is written by a run and rewritten by the next one.
     * `inline`: a refusal belongs in the offer's row while that row is on screen — a swipe, or the
     * archive toast's Restore — rather than beside the detail's button (spec 024).
     *
     * <p>Queued, never dropped: a request for another offer while one is out waits its turn, and
     * only a repeat of an offer already out or waiting is ignored.
     */
    archiveRequested: type<{ id: number; archived: boolean; inline?: boolean }>(),
    /** The queue began the write for this offer; `archiving` names it from here to the answer. */
    archiveStarted: type<number>(),
    archived: type<ShortlistEntry>(),
    archiveFailed: type<ArchiveRefusal>(),
    /** The refusal's line was read: the row it stands in was touched again. */
    archiveErrorDismissed: type<number>(),
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
