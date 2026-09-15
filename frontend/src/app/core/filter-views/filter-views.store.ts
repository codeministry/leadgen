import {DOCUMENT, effect, inject} from '@angular/core';
import {signalStore, withHooks, withState} from '@ngrx/signals';
import {Dispatcher, on, withReducer} from '@ngrx/signals/events';
import {filterViewEvents} from './filter-views.events';
import {FILTER_VIEW_NAME_MAX, FILTER_VIEWS_STORAGE_KEY, FilterView, isFilterView,} from './filter-view.model';

interface FilterViewsState {
  views: readonly FilterView[];
}

/**
 * The shortlist's saved views, kept in this browser.
 *
 * <p>Same shape as `core/theme/theme.store.ts` and for the same reason: the I/O is
 * localStorage rather than HTTP, so it sits in `withHooks` and an `effect` on the state
 * instead of in `withEventHandlers`. Every read and every write is wrapped, because storage
 * throws in a private window and comes back empty after cleared site data — a list of
 * bookmarks is not worth failing a boot over, and the screen has to render without it.
 *
 * <p><b>The URL stays the truth.</b> Applying a view is a navigation and nothing else; the
 * store never holds "which view is showing", because the reader changes one filter a second
 * later and any such flag would then be a lie. A view that no longer matches the URL is
 * simply a view nobody is on.
 */
export const FilterViewsStore = signalStore(
  {providedIn: 'root'},
  withState<FilterViewsState>({views: []}),
  withReducer(
    on(filterViewEvents.restored, ({payload}) => ({views: payload})),
    on(filterViewEvents.saved, ({payload}, state) => ({
      // Appended rather than prepended: the list is read in the order it was built, and
      // a new entry jumping to the top moves every other one under the reader's cursor.
      views: [
        ...state.views,
        {
          id: newId(),
          name: payload.name.trim().slice(0, FILTER_VIEW_NAME_MAX),
          query: payload.query,
        },
      ],
    })),
    on(filterViewEvents.removed, ({payload}, state) => ({
      views: state.views.filter((view) => view.id !== payload),
    })),
  ),
  withHooks({
    onInit(store) {
      const view = inject(DOCUMENT).defaultView;
      inject(Dispatcher).dispatch(filterViewEvents.restored(read(view)));

      effect(() => write(view, store.views()));
    },
  }),
);

/**
 * `randomUUID` is not everywhere — it needs a secure context, and the test environment is
 * not one. The fallback only has to be unique within one browser's list.
 */
function newId(): string {
  const uuid = globalThis.crypto?.randomUUID?.();
  return uuid ?? `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`;
}

function read(view: Window | null): readonly FilterView[] {
  try {
    const stored = view?.localStorage.getItem(FILTER_VIEWS_STORAGE_KEY) ?? null;
    if (stored === null) {
      return [];
    }
    const parsed: unknown = JSON.parse(stored);
    // One bad entry drops itself rather than the whole list: a key half-written by a tab
    // that was closed mid-save should cost one view, not all of them.
    return Array.isArray(parsed) ? parsed.filter(isFilterView) : [];
  } catch {
    return [];
  }
}

function write(view: Window | null, views: readonly FilterView[]): void {
  try {
    view?.localStorage.setItem(FILTER_VIEWS_STORAGE_KEY, JSON.stringify(views));
  } catch {
    // Nothing to do: the list simply will not survive the reload.
  }
}
