import { computed, inject } from '@angular/core';
import { signalStore, withComputed, withState } from '@ngrx/signals';
import { Events, on, withEventHandlers, withReducer } from '@ngrx/signals/events';
import { catchError, exhaustMap, map, of } from 'rxjs';
import { IngestApi, IngestReport } from '@core/api/ingest.api';
import { ingestEvents } from './ingest.events';
import { ScoringModelStore } from './scoring-model.store';

interface IngestState {
  report: IngestReport | null;
  error: string | null;
  running: boolean;
}

const initialState: IngestState = { report: null, error: null, running: false };

/**
 * `POST /api/ingest` runs one pass over every enabled source. `exhaustMap` rather
 * than `switchMap`: a second click while a run is in flight must be ignored, not
 * start a competing pass over the same mailbox.
 */
export const IngestStore = signalStore(
  { providedIn: 'root' },
  withState(initialState),
  withComputed(({ report }) => ({
    /**
     * Documents whose extracted count disagrees with the count they announced.
     * A selector that quietly stops matching looks exactly like a slow market,
     * so this is the one thing the dashboard has to say loudly.
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
  })),
  withReducer(
    on(ingestEvents.requested, () => ({ running: true, error: null })),
    on(ingestEvents.finished, ({ payload }) => ({ report: payload, running: false })),
    on(ingestEvents.failed, ({ payload }) => ({ error: payload, running: false })),
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
    ];
  }),
);
