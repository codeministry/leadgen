import {computed, inject} from '@angular/core';
import {signalStore, withComputed, withHooks, withState} from '@ngrx/signals';
import {Dispatcher, Events, on, withEventHandlers, withReducer} from '@ngrx/signals/events';
import {catchError, exhaustMap, filter, map, of, pairwise, switchMap, timer} from 'rxjs';
import {IngestApi, IngestReport} from '@core/api/ingest.api';
import {serverMessage} from '@core/api/server-message';
import {CurrentRunView} from '@core/model/current-run';
import {LastRunView} from '@core/model/last-run';
import {ingestEvents} from './ingest.events';
import {ScoringModelStore} from './scoring-model.store';

interface IngestState {
    /** What a run this browser started handed back. Null until somebody presses the button. */
    report: IngestReport | null;
    /**
     * What the last run left in the database, whoever started it. Kept apart from `report`
     * rather than folded into it: a recorded run carries no per-document breakdown and no
     * digest path, and the screen has to be able to say which of the two it is showing.
     */
    lastRun: LastRunView | null;
  /**
   * A pass in flight, whoever started it — this browser, another tab, the nightly CronJob.
   * Null when none is. Kept apart from `running`, which says only whether *this* browser is
   * waiting on its own request, and was therefore blind to every other way a run begins.
   */
  current: CurrentRunView | null;
    error: string | null;
    running: boolean;
}

const initialState: IngestState = {
  report: null,
  lastRun: null,
  current: null,
  error: null,
  running: false,
};

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
    {providedIn: 'root'},
    withState(initialState),
  withComputed(({report, lastRun, current, running}) => ({
    /**
     * Whether a pass is going on at all, started here or anywhere else. What the button
     * reads: `running` alone left it enabled during a nightly pass, so the click went out
     * and came back 409 with a sentence the reader had to open the network tab to see.
     */
    busy: computed(() => running() || current() !== null),
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
                    .map((document) => ({sourceId: source.sourceId, document})),
            ),
        ),
        documents: computed(() =>
            (report()?.sources ?? []).reduce((sum, source) => sum + source.documents, 0),
        ),
        /** True while the recorded run is the only thing there is to show. */
        showingRecordedRun: computed(() => report() === null && lastRun() !== null),
    })),
    withReducer(
        on(ingestEvents.requested, () => ({running: true, error: null})),
        on(ingestEvents.finished, ({payload}) => ({report: payload, running: false})),
        on(ingestEvents.failed, ({payload}) => ({error: payload, running: false})),
        on(ingestEvents.lastRunLoaded, ({payload}) => ({lastRun: payload})),
      on(ingestEvents.currentLoaded, ({payload}) => ({current: payload})),
        // Deliberately not written into `error`: that one blanks the run panel, and a
        // dashboard that cannot reach the history is still a dashboard. The tiles fall back to
        // the archive totals, exactly as they did before this existed.
        on(ingestEvents.lastRunFailed, () => ({lastRun: null})),
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
                      // The server's own sentence when it wrote one, the catalog key when it
                      // did not. `exhaustMap` above stops a second click from *this* browser,
                      // and it is the only thing it stops: a scheduled pass, another tab or a
                      // second machine all answer 409 with "an ingest run is already in
                      // progress; this one was not started", which says what happened and what
                      // it did not do. "The ingest run did not answer" said neither, and it was
                      // the one message a reader could act on turned into one they could not.
                      catchError((error) => of(ingestEvents.failed(serverMessage(error, 'error.ingestRun')))),
                    ),
                ),
            ),
          /*
           * A heartbeat, not a screen's question. A run started by the nightly CronJob, by
           * another tab or by a second machine is invisible here otherwise, and a pass takes
           * eleven minutes on the deployed corpus — long enough for "is anything happening"
           * to be the only question worth answering.
           *
           * Five seconds while one is going, thirty when none is. One small indexed row
           * either way, and the slow cadence is what keeps an idle browser from talking to
           * the API twelve times a minute forever. `timer` restarts on every answer rather
           * than `interval` firing regardless, so a slow reply cannot stack requests.
           */
          events.on(ingestEvents.currentRequested).pipe(
            switchMap(() =>
              api.current().pipe(
                map((run) => ingestEvents.currentLoaded(run)),
                // Silent: a heartbeat that cannot reach the API must not put an error
                // on a dashboard that is otherwise fine. `null` is also the honest
                // answer — nothing is known to be running.
                catchError(() => of(ingestEvents.currentLoaded(null))),
              ),
            ),
          ),
          /*
           * The heartbeat is self-restarting rather than an `interval` firing regardless:
           * a slow reply cannot stack requests behind it, and the cadence can depend on the
           * answer. Five seconds while a pass is going, thirty when none is — the fast one
           * is what makes a stage change visible, the slow one is what keeps an idle
           * browser from talking to the API twelve times a minute forever.
           */
          events.on(ingestEvents.currentLoaded).pipe(
            switchMap(({payload}) =>
              timer(payload === null ? 30_000 : 5_000).pipe(map(() => ingestEvents.currentRequested())),
            ),
          ),
          /*
           * A pass that was going and now is not: read what it left behind. Without this the
           * dashboard shows last night's numbers until somebody reloads, which is the defect
           * `/api/ingest/last` was added for, one level up — there it was a run this browser
           * never saw, here it is a run this browser watched end.
           *
           * `pairwise` rather than a look at the store, because the order in which a reducer
           * and a handler see the same event is not something to depend on.
           */
          events.on(ingestEvents.currentLoaded).pipe(
            map(({payload}) => payload !== null),
            pairwise(),
            filter(([was, is]) => was && !is),
            map(() => ingestEvents.lastRunRequested()),
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
          const dispatcher = inject(Dispatcher);
          dispatcher.dispatch(ingestEvents.lastRunRequested());
          dispatcher.dispatch(ingestEvents.currentRequested());
        },
    }),
);
