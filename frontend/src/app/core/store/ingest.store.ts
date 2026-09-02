import { computed, inject } from '@angular/core';
import { signalStore, withComputed, withHooks, withState } from '@ngrx/signals';
import { Dispatcher, Events, on, withEventHandlers, withReducer } from '@ngrx/signals/events';
import { catchError, exhaustMap, map, of, switchMap } from 'rxjs';
import { IngestApi, IngestReport } from '@core/api/ingest.api';
import { LastRunView } from '@core/model/last-run';
import { ingestEvents } from './ingest.events';
import { ScoringModelStore } from './scoring-model.store';

interface IngestState {
  /** What a run this browser started handed back. Null until somebody presses the button. */
  report: IngestReport | null;
  /**
   * What the last run left in the database, whoever started it. Kept apart from `report`
   * rather than folded into it: a recorded run carries no per-document breakdown and no
   * digest path, and the screen has to be able to say which of the two it is showing.
   */
  lastRun: LastRunView | null;
  error: string | null;
  running: boolean;
}

const initialState: IngestState = { report: null, lastRun: null, error: null, running: false };

/**
 * `POST /api/ingest` runs one pass over every enabled source. `exhaustMap` rather
 * than `switchMap`: a second click while a run is in flight must be ignored, not
 * start a competing pass over the same mailbox.
 *
 * `GET /api/ingest/last` answers the other half. Without it the dashboard knew about a run
 * only if this browser had started one — measured on 2026-09-02, a `pipeline_run` row six
 * minutes old and the screen saying "No run yet". After a scheduled nightly pass that is
 * every morning, on the one screen whose subtitle is "what came in this morning".
 */
export const IngestStore = signalStore(
  { providedIn: 'root' },
  withState(initialState),
  withComputed(({ report, lastRun }) => ({
    /**
     * Documents whose extracted count disagrees with the count they announced.
     * A selector that quietly stops matching looks exactly like a slow market,
     * so this is the one thing the dashboard has to say loudly.
     *
     * Only ever from `report`: `source_run` records one row per source and not one per
     * document, so a recorded run can name the source but never the document. The source
     * rows carry their own `complete` flag for that case.
     */
    mismatches: computed(() =>
      (report()?.sources ?? []).flatMap((source) =>
        source.details
          .filter((document) => document.announced !== null && !document.complete)
          .map((document) => ({ sourceId: source.sourceId, document })),
      ),
    ),
    documents: computed(() =>
      (report()?.sources ?? []).reduce((sum, source) => sum + source.documents, 0),
    ),
    /** True while the recorded run is the only thing there is to show. */
    showingRecordedRun: computed(() => report() === null && lastRun() !== null),
  })),
  withReducer(
    on(ingestEvents.requested, () => ({ running: true, error: null })),
    on(ingestEvents.finished, ({ payload }) => ({ report: payload, running: false })),
    on(ingestEvents.failed, ({ payload }) => ({ error: payload, running: false })),
    on(ingestEvents.lastRunLoaded, ({ payload }) => ({ lastRun: payload })),
    // Deliberately not written into `error`: that one blanks the run panel, and a
    // dashboard that cannot reach the history is still a dashboard. The tiles fall back to
    // the archive totals, exactly as they did before this existed.
    on(ingestEvents.lastRunFailed, () => ({ lastRun: null })),
  ),
  withEventHandlers(() => {
    const events = inject(Events);
    const api = inject(IngestApi);
    // Read here rather than carried on the event: the choice belongs to the moment the
    // request leaves, and a header that had to hand it over would be the second place
    // that knows which models exist.
    const models = inject(ScoringModelStore);

    return [
      events.on(ingestEvents.requested).pipe(
        exhaustMap(() =>
          api.run(models.effective()).pipe(
            map((report) => ingestEvents.finished(report)),
            catchError(() => of(ingestEvents.failed('error.ingestRun'))),
          ),
        ),
      ),
      events.on(ingestEvents.lastRunRequested).pipe(
        switchMap(() =>
          api.last().pipe(
            map((run) => ingestEvents.lastRunLoaded(run)),
            catchError(() => of(ingestEvents.lastRunFailed('error.lastRun'))),
          ),
        ),
      ),
    ];
  }),
  withHooks({
    onInit() {
      // Asked once, when the store is created, rather than from the dashboard's `ngOnInit`:
      // the answer is the same for every screen that ever wants it, and a screen asking on
      // every visit would re-fetch a row that only changes when a run happens.
      inject(Dispatcher).dispatch(ingestEvents.lastRunRequested());
    },
  }),
);
