import {DOCUMENT, inject} from '@angular/core';
import {signalStore, withHooks} from '@ngrx/signals';
import {Dispatcher, Events, withEventHandlers} from '@ngrx/signals/events';
import {filter, map, pairwise} from 'rxjs';
import {ingestEvents} from '@core/store/ingest.events';
import {refreshEvents} from './refresh.events';

/**
 * How long the tab has to have been away before coming back counts as a reason to re-read.
 *
 * Alt-tabbing to a terminal and back is not news, and refreshing every screen on every glance
 * would put the app's most expensive answers on a hair trigger. Half a minute is long enough
 * that something could plausibly have happened and short enough that a coffee counts.
 */
const AWAY_LONG_ENOUGH_MS = 30_000;

/**
 * Notices that the data has probably changed, and says so once.
 *
 * <p>It holds no state of its own — it is the one place the two triggers live, so that eight
 * stores do not each grow their own idea of when to look again.
 *
 * <p><b>A pass ending is detected here and not in `IngestStore`</b>, although the heartbeat
 * that sees it belongs to that store. The transition is the interesting thing to more than
 * one reader, and a signal several stores act on should be raised where it is raised for all
 * of them rather than as a side effect inside the store that happened to notice.
 */
export const RefreshStore = signalStore(
  {providedIn: 'root'},
  withEventHandlers(() => {
    const events = inject(Events);

    return [
      /*
       * A pass that was going and now is not. `pairwise` rather than a look at the
       * store, because the order in which a reducer and a handler see the same event is
       * not something to depend on — and because the transition, not the state, is the
       * news.
       *
       * A run this browser started raises this too, a moment after its own
       * `ingestEvents.finished`. Harmless: every store that reacts to both is
       * idempotent, and the alternative is this store knowing who clicked what.
       */
      events.on(ingestEvents.currentLoaded).pipe(
        map(({payload}) => payload !== null),
        pairwise(),
        filter(([was, is]) => was && !is),
        map(() => refreshEvents.requested('run-ended')),
      ),
    ];
  }),
  withHooks({
    onInit() {
      const document = inject(DOCUMENT);
      const dispatcher = inject(Dispatcher);
      const view = document.defaultView;
      // jsdom has the event but no meaningful visibility, and a spec must not depend on
      // either. Guarded for the same reason `matchMedia` is on the shortlist.
      if (view === null || typeof document.addEventListener !== 'function') {
        return;
      }

      let hiddenAt: number | null = null;
      document.addEventListener('visibilitychange', () => {
        if (document.visibilityState === 'hidden') {
          hiddenAt = Date.now();
          return;
        }
        const away = hiddenAt === null ? 0 : Date.now() - hiddenAt;
        hiddenAt = null;
        if (away >= AWAY_LONG_ENOUGH_MS) {
          dispatcher.dispatch(refreshEvents.requested('tab-focused'));
        }
      });
    },
  }),
);
