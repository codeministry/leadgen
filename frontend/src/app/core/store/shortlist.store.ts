import { computed, inject } from '@angular/core';
import { signalStore, withComputed, withState } from '@ngrx/signals';
import { Events, on, withEventHandlers, withReducer } from '@ngrx/signals/events';
import { catchError, exhaustMap, map, of, switchMap } from 'rxjs';
import { ShortlistApi } from '@core/api/shortlist.api';
import { serverMessage } from '@core/api/server-message';
import { ingestEvents } from './ingest.events';
import { FunnelView } from '@core/model/funnel';
import { ShortlistEntry } from '@core/model/shortlist-entry';
import { ShortlistFilters } from '@core/model/shortlist-page';
import { ScoringModelStore } from './scoring-model.store';
import { shortlistEvents } from './shortlist.events';

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
  error: string | null;
  loading: boolean;
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
}

const NO_FILTERS: ShortlistFilters = { q: '', band: 'all', portal: '', archived: false };

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
  error: null,
  loading: false,
  rescoring: null,
  rescoreError: null,
  archiving: null,
};

export const ShortlistStore = signalStore(
  { providedIn: 'root' },
  withState(initialState),
  withComputed(({ cursor }) => ({
    // Both the portals and the unscored count come from the server now. Derived from the
    // loaded entries, the dropdown offered fewer choices and the count told a smaller
    // truth the further you scrolled — and both sit beside a sentence about the whole list.
    hasMore: computed(() => cursor() !== null),
  })),
  withReducer(
    // A filter change is a new list, not a longer one: the entries go before the request
    // rather than after it, so the page never shows the previous filter's offers under the
    // new filter's heading.
    on(shortlistEvents.opened, ({ payload }) => ({
      loading: true,
      error: null,
      entries: [],
      cursor: null,
      filters: payload,
    })),
    on(shortlistEvents.loaded, ({ payload }) => ({
      entries: payload.entries,
      cursor: payload.nextCursor,
      matched: payload.matched,
      unscored: payload.unscored,
      total: payload.total,
      portals: payload.portals,
      loading: false,
    })),
    on(shortlistEvents.moreRequested, () => ({ loadingMore: true })),
    on(shortlistEvents.moreLoaded, ({ payload }, state) => ({
      entries: [...state.entries, ...payload.entries],
      cursor: payload.nextCursor,
      matched: payload.matched,
      unscored: payload.unscored,
      total: payload.total,
      loadingMore: false,
    })),
    on(shortlistEvents.failed, ({ payload }) => ({ error: payload, loading: false })),
    // Cleared on request, not on arrival: leaving the previous offer on screen while the
    // next one loads shows the wrong ad under the right title.
    on(shortlistEvents.offerRequested, () => ({ selected: null, loading: true, error: null })),
    on(shortlistEvents.offerLoaded, ({ payload }) => ({ selected: payload, loading: false })),
    on(shortlistEvents.offerFailed, ({ payload }) => ({ error: payload, loading: false })),
    on(shortlistEvents.funnelLoaded, ({ payload }) => ({ funnel: payload })),
    on(shortlistEvents.rescoreRequested, ({ payload }) => ({
      rescoring: payload,
      rescoreError: null,
    })),
    // Both the detail and the list row are replaced with what the server stored, never
    // with what was asked for: the score is computed there, and a locally patched row
    // would disagree with the database until the next reload.
    on(shortlistEvents.rescored, ({ payload }, state) => ({
      selected: payload,
      entries: state.entries.map((entry) => (entry.offer.id === payload.offer.id ? payload : entry)),
      rescoring: null,
    })),
    on(shortlistEvents.rescoreFailed, ({ payload }) => ({
      rescoring: null,
      rescoreError: payload,
    })),
    on(shortlistEvents.archiveRequested, ({ payload }) => ({
      archiving: payload.id,
      rescoreError: null,
    })),
    // The row is dropped from the list rather than replaced: archiving is what takes an
    // offer off the side being read, so leaving it there would show the working list with
    // something on it that is no longer part of it — until a reload said otherwise. The
    // detail keeps the entry, because that screen shows either side.
    on(shortlistEvents.archived, ({ payload }, state) => ({
      selected: payload,
      entries: state.entries.filter((entry) => entry.offer.id !== payload.offer.id),
      matched: Math.max(0, state.matched - 1),
      total: Math.max(0, state.total - 1),
      archiving: null,
    })),
    on(shortlistEvents.archiveFailed, ({ payload }) => ({
      archiving: null,
      rescoreError: payload,
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
        switchMap(({ payload }) =>
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
        switchMap(({ payload }) =>
          api.one(payload).pipe(
            map((entry) => shortlistEvents.offerLoaded(entry)),
            catchError(() => of(shortlistEvents.offerFailed('error.offerLoad'))),
          ),
        ),
      ),
      // Exhausted, not switched: a second click while the first call is out would write
      // the same decision twice, and the second answer would arrive after the row is gone.
      events.on(shortlistEvents.archiveRequested).pipe(
        exhaustMap(({ payload }) =>
          api.setArchived(payload.id, payload.archived).pipe(
            map((entry) => shortlistEvents.archived(entry)),
            catchError((error) =>
              of(shortlistEvents.archiveFailed(serverMessage(error, 'error.archive'))),
            ),
          ),
        ),
      ),
      // Exhausted, not switched: this one spends money. A second click while the first
      // call is still out is a second language-model call for the same answer.
      events.on(shortlistEvents.rescoreRequested).pipe(
        exhaustMap(({ payload }) =>
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
