import {computed, inject} from '@angular/core';
import {signalStore, withComputed, withState} from '@ngrx/signals';
import {Events, on, withEventHandlers, withReducer} from '@ngrx/signals/events';
import {catchError, exhaustMap, filter, map, of, switchMap} from 'rxjs';
import {ShortlistApi} from '@core/api/shortlist.api';
import {serverMessage} from '@core/api/server-message';
import {ingestEvents} from './ingest.events';
import {FunnelView} from '@core/model/funnel';
import {ShortlistEntry} from '@core/model/shortlist-entry';
import {ShortlistFilters} from '@core/model/shortlist-page';
import {ScoringModelStore} from './scoring-model.store';
import {shortlistEvents} from './shortlist.events';

interface ShortlistState {
    entries: readonly ShortlistEntry[];
    /** What the current entries were loaded for, so a page continues the right list. */
    filters: ShortlistFilters;
    cursor: string | null;
    matched: number;
    unscored: number;
    total: number;
    portals: readonly string[];
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
}

const NO_FILTERS: ShortlistFilters = {q: '', band: 'all', portal: '', archived: false};

const initialState: ShortlistState = {
    entries: [],
    filters: NO_FILTERS,
    cursor: null,
    matched: 0,
    unscored: 0,
    total: 0,
    portals: [],
    loadingMore: false,
    selected: null,
    funnel: null,
    listLoading: false,
    listError: null,
    detailLoading: false,
    detailError: null,
    rescoring: null,
    rescoreError: null,
    archiving: null,
  picked: [],
  pickAnchor: null,
  bulkArchiving: false,
  bulkArchiveError: null,
};

export const ShortlistStore = signalStore(
    {providedIn: 'root'},
    withState(initialState),
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
        on(shortlistEvents.loaded, ({payload}) => ({
            entries: payload.entries,
            cursor: payload.nextCursor,
            matched: payload.matched,
            unscored: payload.unscored,
            total: payload.total,
            portals: payload.portals,
            listLoading: false,
        })),
        on(shortlistEvents.moreRequested, () => ({loadingMore: true})),
      // `picked` and `pickAnchor` are deliberately untouched here, and the absence is worth
      // a sentence because it is invisible: a longer list is the same list, so what was
      // ticked stays ticked. It is also what lets a Shift-range span a page boundary.
        on(shortlistEvents.moreLoaded, ({payload}, state) => ({
            entries: [...state.entries, ...payload.entries],
            cursor: payload.nextCursor,
            matched: payload.matched,
            unscored: payload.unscored,
            total: payload.total,
            loadingMore: false,
        })),
        on(shortlistEvents.failed, ({payload}) => ({listError: payload, listLoading: false})),
        // Cleared on request, not on arrival: leaving the previous offer on screen while the
        // next one loads shows the wrong ad under the right title.
        on(shortlistEvents.offerRequested, () => ({
            selected: null,
            detailLoading: true,
            detailError: null,
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
        on(shortlistEvents.rescored, ({payload}, state) => ({
            selected: payload,
            entries: state.entries.map((entry) =>
                entry.offer.id === payload.offer.id ? payload : entry,
            ),
            rescoring: null,
        })),
        on(shortlistEvents.rescoreFailed, ({payload}) => ({
            rescoring: null,
            rescoreError: payload,
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
        ];
    }),
);
