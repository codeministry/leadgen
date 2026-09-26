import {computed, inject} from '@angular/core';
import {signalStore, withComputed, withState} from '@ngrx/signals';
import {Events, on, withEventHandlers, withReducer} from '@ngrx/signals/events';
import {catchError, exhaustMap, filter, map, of, switchMap} from 'rxjs';
import {ShortlistApi} from '@core/api/shortlist.api';
import {serverMessage} from '@core/api/server-message';
import {refreshEvents} from '@core/refresh/refresh.events';
import {ingestEvents} from './ingest.events';
import {FunnelView} from '@core/model/funnel';
import {ShortlistEntry} from '@core/model/shortlist-entry';
import {RelatedCoverage, ShortlistFilters} from '@core/model/shortlist-page';
import {ScoringModelStore} from './scoring-model.store';
import {shortlistEvents} from './shortlist.events';
import {withAppDevtools} from '@core/store/devtools';

interface ShortlistState {
    entries: readonly ShortlistEntry[];
    /** What the current entries were loaded for, so a page continues the right list. */
    filters: ShortlistFilters;
    cursor: string | null;
    matched: number;
    unscored: number;
    total: number;
    portals: readonly string[];
    /**
     * How far the relatedness filter can see, or null when this installation cannot answer
     * one at all. The null is the capability flag, and it is what lets the screen leave the
     * control out rather than offer one the server would refuse.
     */
    relatedCoverage: RelatedCoverage | null;
    /** The title of the offer a `similar=` filter is anchored on, so the chip can name it. */
    relatedTo: string | null;
    loadingMore: boolean;
    /** The offer the detail is showing, fetched by id rather than found in the list. */
    selected: ShortlistEntry | null;
    funnel: FunnelView | null;
    /**
     * Four fields where there were two. The list and the detail share a screen now, and they
     * used to share one `loading` and one `error`: a detail fetch that failed set `error`,
     * the list's template branched on `error` first, and one bad id blanked the whole list
     * beside it. `listError` replaces the list, `detailError` replaces the right column, and
     * neither reaches the other. Same reason `rescoreError` was kept apart below.
     */
    listLoading: boolean;
    listError: string | null;
    detailLoading: boolean;
    detailError: string | null;
    /**
     * The offer being judged again, not a boolean. One button shows a spinner; every other
     * control on the page stays usable, and the same rule the board follows for `saving`.
     */
    rescoring: number | null;
    /**
     * Kept apart from `error`, which blanks the page. A refused rescore is a sentence beside
     * the button; the offer on screen is still the right one and still worth reading.
     */
    rescoreError: string | null;
    /** The offer whose ad is being fetched again, not a boolean, for the reason `rescoring` is not. */
    fetching: number | null;
    /**
     * A fetch the server turned away, as a sentence beside the button. Apart from
     * `rescoreError` so that one button's refusal never appears under the other.
     */
    fetchError: string | null;
    /** The offer being archived or restored, not a boolean. One button waits, not the page. */
    archiving: number | null;
  /**
   * The offers ticked for a bulk decision. Ids and not indices: `entries` is appended to by
   * paging and shortened by an archive, and an index silently points at a different offer
   * after either.
   *
   * Here rather than in the component because `opened` already is the clearing event — it
   * fires off the same computed the filters do, so the selection cannot clear at a different
   * moment than the list it belongs to. This is not the routed selection: which offer is
   * *open* is the URL, because a deep link, the back button and a click all have to agree on
   * it. Which offers are ticked is transient, and fifty ids in a query string is not a link
   * anybody sends.
   */
  picked: readonly number[];
  /**
   * Where a Shift-click measures from — the last card toggled on its own. Beside `picked` so
   * that one reducer clears both: a range measured from an anchor the filter change removed
   * would select offers nobody pointed at.
   */
  pickAnchor: number | null;
  /** `archiving` names an offer, and a set is not an offer. */
  bulkArchiving: boolean;
  /**
   * Apart from `rescoreError` for the reason `listError` and `detailError` were split: this
   * one belongs beside the action bar in the list column, not beside the detail's buttons.
   */
  bulkArchiveError: string | null;
  /**
   * Whether something happened that this list has not read yet.
   *
   * <p>A flag and not a reload, and this is the one screen where the difference matters. The
   * list is keyset-paged, so re-reading it means `opened`, which empties `entries` and starts
   * again at page one — a reader forty offers down is returned to the top for news they did
   * not ask about. Every other screen re-reads silently because nothing there is lost.
   */
  stale: boolean;
}

const NO_FILTERS: ShortlistFilters = {
  q: '',
  band: 'all',
  minScore: null,
  maxScore: null,
  scoreState: 'any',
  portals: [],
  archived: false,
  sort: 'fresh',
  startWindow: 'any',
  minMonths: 0,
  deadlineOpen: false,
  possibleDuplicates: false,
  topic: '',
  semantic: '',
  similarTo: null,
};

const initialState: ShortlistState = {
    entries: [],
    filters: NO_FILTERS,
    cursor: null,
    matched: 0,
    unscored: 0,
    total: 0,
    portals: [],
    relatedCoverage: null,
    relatedTo: null,
    loadingMore: false,
    selected: null,
    funnel: null,
    listLoading: false,
    listError: null,
    detailLoading: false,
    detailError: null,
    rescoring: null,
    rescoreError: null,
    fetching: null,
    fetchError: null,
    archiving: null,
  picked: [],
  pickAnchor: null,
  bulkArchiving: false,
  bulkArchiveError: null,
  stale: false,
};

export const ShortlistStore = signalStore(
    {providedIn: 'root'},
    withState(initialState),
    withAppDevtools('shortlist'),
  withComputed(({cursor, picked}) => ({
        // Both the portals and the unscored count come from the server now. Derived from the
        // loaded entries, the dropdown offered fewer choices and the count told a smaller
        // truth the further you scrolled — and both sit beside a sentence about the whole list.
        hasMore: computed(() => cursor() !== null),
    // A Set, because every card asks whether it is ticked on every pass, and a linear
    // scan per row over the selection is quadratic in a list that pages to hundreds.
    pickedIds: computed(() => new Set(picked())),
    pickedCount: computed(() => picked().length),
    })),
    withReducer(
        // A filter change is a new list, not a longer one: the entries go before the request
        // rather than after it, so the page never shows the previous filter's offers under the
        // new filter's heading.
      // The selection goes with them, and that is the whole of "the archive side offers no
      // bulk restore" on this side: `archived` is one of the four filters, so crossing to
      // the archive empties the picks through the mechanism that already exists. A future
      // reader will look for a special case; there is none, deliberately.
        on(shortlistEvents.opened, ({payload}) => ({
            listLoading: true,
            listError: null,
            entries: [],
            cursor: null,
            filters: payload,
          picked: [],
          pickAnchor: null,
          bulkArchiveError: null,
        })),
      // `stale` is cleared by a page arriving, whatever asked for it. Self-correcting, and
      // it is what stops the reader's own run from leaving the hint behind: the reload that
      // `ingestEvents.finished` starts clears it without anyone having to sequence the two.
        on(shortlistEvents.loaded, ({payload}) => ({
          stale: false,
            entries: payload.entries,
            cursor: payload.nextCursor,
            matched: payload.matched,
            unscored: payload.unscored,
            total: payload.total,
            portals: payload.portals,
            relatedCoverage: payload.related,
            relatedTo: payload.relatedTo,
            listLoading: false,
        })),
        on(shortlistEvents.moreRequested, () => ({loadingMore: true})),
      // `picked` and `pickAnchor` are deliberately untouched here, and the absence is worth
      // a sentence because it is invisible: a longer list is the same list, so what was
      // ticked stays ticked. It is also what lets a Shift-range span a page boundary.
      // `matched` and `unscored` are deliberately not written here either, and for the same
      // reason: a longer list is the same match. They belong to the filters, not to how far
      // somebody has scrolled — which is the whole argument that moved them to the server.
        on(shortlistEvents.moreLoaded, ({payload}, state) => ({
            entries: [...state.entries, ...payload.entries],
            cursor: payload.nextCursor,
            total: payload.total,
            loadingMore: false,
        })),
        on(shortlistEvents.failed, ({payload}) => ({listError: payload, listLoading: false})),
      on(shortlistEvents.wentStale, () => ({stale: true})),
        // Cleared on request, not on arrival: leaving the previous offer on screen while the
        // next one loads shows the wrong ad under the right title.
        on(shortlistEvents.offerRequested, () => ({
            selected: null,
            detailLoading: true,
            detailError: null,
            rescoreError: null,
            fetchError: null,
        })),
        on(shortlistEvents.offerLoaded, ({payload}) => ({
            selected: payload,
            detailLoading: false,
        })),
        on(shortlistEvents.offerFailed, ({payload}) => ({
            detailError: payload,
            detailLoading: false,
        })),
        on(shortlistEvents.funnelLoaded, ({payload}) => ({funnel: payload})),
        on(shortlistEvents.rescoreRequested, ({payload}) => ({
            rescoring: payload,
            rescoreError: null,
        })),
        // Both the detail and the list row are replaced with what the server stored, never
        // with what was asked for: the score is computed there, and a locally patched row
        // would disagree with the database until the next reload.
        // The detail only while it still shows that offer — the guard `fetched` has, for the
        // same race: a rescore is a model call, and one that lands after the reader moved on
        // would put the first offer's score under the second one's title.
        on(shortlistEvents.rescored, ({payload}, state) => ({
            selected: state.selected?.offer.id === payload.offer.id ? payload : state.selected,
            entries: state.entries.map((entry) =>
                entry.offer.id === payload.offer.id ? payload : entry,
            ),
            rescoring: null,
        })),
        // The refusal names the offer that was asked about, which `rescoring` still holds;
        // shown under the next offer's title it would read as that offer's problem.
        on(shortlistEvents.rescoreFailed, ({payload}, state) => ({
            rescoring: null,
            rescoreError: state.selected?.offer.id === state.rescoring ? payload : state.rescoreError,
        })),
        on(shortlistEvents.fetchRequested, ({payload}) => ({
            fetching: payload,
            fetchError: null,
        })),
        // Replaced with what the server stored, like a rescore: the fetch rewrote the advert,
        // its blocks, its fields and its score, and none of that is the browser's to guess.
        // The detail only when it still shows that offer: a fetch takes seconds, and one that
        // lands after the reader moved on would put its advert under the next offer's title.
        on(shortlistEvents.fetched, ({payload}, state) => ({
            selected: state.selected?.offer.id === payload.offer.id ? payload : state.selected,
            entries: state.entries.map((entry) =>
                entry.offer.id === payload.offer.id ? payload : entry,
            ),
            fetching: null,
        })),
        on(shortlistEvents.fetchFailed, ({payload}) => ({
            fetching: null,
            fetchError: payload,
        })),
        on(shortlistEvents.archiveRequested, ({payload}) => ({
            archiving: payload.id,
            rescoreError: null,
        })),
        // The row is dropped from the list rather than replaced: archiving is what takes an
        // offer off the side being read, so leaving it there would show the working list with
        // something on it that is no longer part of it — until a reload said otherwise. The
        // detail keeps the entry, because that screen shows either side.
        on(shortlistEvents.archived, ({payload}, state) => ({
            selected: payload,
            entries: state.entries.filter((entry) => entry.offer.id !== payload.offer.id),
            matched: Math.max(0, state.matched - 1),
            total: Math.max(0, state.total - 1),
            archiving: null,
        })),
        on(shortlistEvents.archiveFailed, ({payload}) => ({
            archiving: null,
            rescoreError: payload,
        })),
      // The anchor moves on an untick as well: "tick 5, untick 5, shift-click 12" means
      // 5 through 12, not a reach back to whatever happened to be ticked before that.
      on(shortlistEvents.offerPicked, ({payload}, state) => ({
        picked: payload.picked
          ? [...state.picked, payload.id]
          : state.picked.filter((id) => id !== payload.id),
        pickAnchor: payload.id,
      })),
      // A union, never a replacement: a Shift-click extends a selection, it does not become
      // one, and somebody ticking three cards and then shift-clicking a fourth block means
      // both.
      on(shortlistEvents.rangePicked, ({payload}, state) => ({
        picked: [...new Set([...state.picked, ...payload])],
        pickAnchor: payload.at(-1) ?? state.pickAnchor,
      })),
      on(shortlistEvents.picksCleared, () => ({picked: [], pickAnchor: null})),
      on(shortlistEvents.bulkArchiveRequested, () => ({
        bulkArchiving: true,
        bulkArchiveError: null,
      })),
      // The counts move by what the server wrote, never by the selection's size: an id that
      // named no row was not archived, and both are clamped at zero, so the error would hide
      // itself and only on the day an id was stale.
      //
      // `selected` is deliberately left alone. The single path replaces it with the fresh
      // entry so the detail's button flips to Restore; here there are no entries to replace
      // it with, and patching it locally is what the rescore reducer forbids. A handler asks
      // for it again instead — which only works because this reducer has not cleared it yet.
      on(shortlistEvents.bulkArchived, ({payload}, state) => {
        const gone = new Set(payload.ids);
        return {
          entries: state.entries.filter((entry) => !gone.has(entry.offer.id)),
          matched: Math.max(0, state.matched - payload.archived),
          total: Math.max(0, state.total - payload.archived),
          unscored: Math.max(0, state.unscored - payload.unscored),
          picked: [],
          pickAnchor: null,
          bulkArchiving: false,
        };
      }),
      on(shortlistEvents.bulkArchiveFailed, ({payload}) => ({
        bulkArchiving: false,
        bulkArchiveError: payload,
      })),
    ),
    withEventHandlers((store) => {
        const events = inject(Events);
        const api = inject(ShortlistApi);
        // The rescore spends money too, so it asks the same judge the run would.
        const models = inject(ScoringModelStore);

        return [
            // Switched, not exhausted: typing in the search box replaces the question, and the
            // answer to the previous keystroke is worth nothing.
            events.on(shortlistEvents.opened).pipe(
                switchMap(({payload}) =>
                    api.page(payload, null).pipe(
                        map((page) => shortlistEvents.loaded(page)),
                        catchError(() => of(shortlistEvents.failed('error.shortlistLoad'))),
                    ),
                ),
            ),
            // Exhausted: two sentinel crossings in one scroll must not fetch the same page twice.
            events.on(shortlistEvents.moreRequested).pipe(
                exhaustMap(() =>
                    api.page(store.filters(), store.cursor()).pipe(
                        map((page) => shortlistEvents.moreLoaded(page)),
                        catchError(() => of(shortlistEvents.failed('error.shortlistLoad'))),
                    ),
                ),
            ),
            events.on(shortlistEvents.funnelOpened).pipe(
                exhaustMap(() =>
                    api.funnel().pipe(
                        map((funnel) => shortlistEvents.funnelLoaded(funnel)),
                        catchError(() => of(shortlistEvents.failed('error.funnelLoad'))),
                    ),
                ),
            ),
            // A run rewrites everything this store shows, and the screens load once on init:
            // without this the shortlist a person is looking at while the run finishes is the
            // one from before it. The reload is expressed as this store's own load event, so
            // there is one path that fetches and `ingest` knows nothing about who listens.
            events.on(ingestEvents.finished).pipe(map(() => shortlistEvents.opened(store.filters()))),
            events.on(ingestEvents.finished).pipe(map(() => shortlistEvents.funnelOpened())),
          // Anything else that says the data moved only *flags* this list. The funnel is
          // re-read either way: it is four numbers above the column and nothing about it is
          // lost by reading it again, while the list underneath would lose the reader's
          // place. The hint says so and a click does the rest.
          events.on(refreshEvents.requested).pipe(map(() => shortlistEvents.wentStale())),
          events.on(refreshEvents.requested).pipe(map(() => shortlistEvents.funnelOpened())),
            // Switched, not exhausted: clicking through two offers quickly must end on the
            // second one, and the first answer is then worth nothing.
            events.on(shortlistEvents.offerRequested).pipe(
                switchMap(({payload}) =>
                    api.one(payload).pipe(
                        map((entry) => shortlistEvents.offerLoaded(entry)),
                        catchError(() => of(shortlistEvents.offerFailed('error.offerLoad'))),
                    ),
                ),
            ),
            // Exhausted, not switched: a second click while the first call is out would write
            // the same decision twice, and the second answer would arrive after the row is gone.
            events.on(shortlistEvents.archiveRequested).pipe(
                exhaustMap(({payload}) =>
                    api.setArchived(payload.id, payload.archived).pipe(
                        map((entry) => shortlistEvents.archived(entry)),
                        catchError((error) =>
                            of(shortlistEvents.archiveFailed(serverMessage(error, 'error.archive'))),
                        ),
                    ),
                ),
            ),
          // Exhausted, not switched: a second confirmation while the first call is out would
          // write the same decision twice, and the second answer would arrive after the rows
          // are already gone from the list.
          events.on(shortlistEvents.bulkArchiveRequested).pipe(
            exhaustMap(({payload}) =>
              api.archiveAll(payload).pipe(
                map((result) =>
                  shortlistEvents.bulkArchived({
                    ids: payload,
                    archived: result.archived,
                    unscored: result.unscored,
                  }),
                ),
                catchError((error) =>
                  of(shortlistEvents.bulkArchiveFailed(serverMessage(error, 'error.bulkArchive'))),
                ),
              ),
            ),
          ),
          // The bulk answer carries no entries, so the offer the detail is showing would go
          // on offering to archive an offer that is already archived — a button that lies,
          // the same failure a restore the next run undoes would be. It is fetched again
          // through the path that already owns `detailLoading` and `detailError`, and the
          // reducer's untouched `selected` is what this reads.
          events.on(shortlistEvents.bulkArchived).pipe(
            map(({payload}) => ({ids: payload.ids, open: store.selected()?.offer.id ?? null})),
            filter(({ids, open}) => open !== null && ids.includes(open)),
            map(({open}) => shortlistEvents.offerRequested(open as number)),
          ),
            // Exhausted, not switched: this one spends money. A second click while the first
            // call is still out is a second language-model call for the same answer.
            events.on(shortlistEvents.rescoreRequested).pipe(
                exhaustMap(({payload}) =>
                    api.rescore(payload, models.effective()).pipe(
                        map((entry) => shortlistEvents.rescored(entry)),
                        // The server refuses a rescore with its reason as plain text — no model
                        // configured, or an offer the filter rejected. That sentence is the whole
                        // answer to "why did nothing happen".
                        catchError((error) =>
                            of(shortlistEvents.rescoreFailed(serverMessage(error, 'error.rescore'))),
                        ),
                    ),
                ),
            ),
            // Exhausted for the same reason: a second click while the first fetch is out would
            // spend a second request of the minute on the same page.
            events.on(shortlistEvents.fetchRequested).pipe(
                exhaustMap(({payload}) =>
                    api.refetch(payload).pipe(
                        map((entry) => shortlistEvents.fetched(entry)),
                        // Turned away with a sentence: not an offer to fetch, or the minute's
                        // fetches are spent. A page that refused again is not this — it arrives
                        // as `fetched`, with its reason in the enrichment note.
                        catchError((error) =>
                            of(shortlistEvents.fetchFailed(serverMessage(error, 'error.fetch'))),
                        ),
                    ),
                ),
            ),
        ];
    }),
);
