import { inject } from '@angular/core';
import { signalStore, withState } from '@ngrx/signals';
import { Events, on, withEventHandlers, withReducer } from '@ngrx/signals/events';
import { catchError, exhaustMap, map, of } from 'rxjs';
import { ConfigApi } from '@core/api/config.api';
import { RulesView } from '@core/model/rules-view';
import { SourceSummary } from '@core/model/source-summary';
import { configEvents } from './config.events';
import { ingestEvents } from './ingest.events';

interface ConfigState {
  sources: readonly SourceSummary[];
  rules: RulesView | null;
  loading: boolean;
  error: string | null;
}

const initialState: ConfigState = { sources: [], rules: null, loading: false, error: null };

export const ConfigStore = signalStore(
  { providedIn: 'root' },
  withState(initialState),
  withReducer(
    on(configEvents.sourcesOpened, configEvents.rulesOpened, () => ({ loading: true, error: null })),
    on(configEvents.sourcesLoaded, ({ payload }) => ({ sources: payload, loading: false })),
    on(configEvents.rulesLoaded, ({ payload }) => ({ rules: payload, loading: false })),
    on(configEvents.failed, ({ payload }) => ({ error: payload, loading: false })),
  ),
  withEventHandlers(() => {
    const events = inject(Events);
    const api = inject(ConfigApi);

    return [
      events.on(configEvents.sourcesOpened).pipe(
        exhaustMap(() =>
          api.sources().pipe(
            map((sources) => configEvents.sourcesLoaded(sources)),
            catchError(() => of(configEvents.failed('error.sourcesLoad'))),
          ),
        ),
      ),
      // The sources screen counts documents, offers and survivors per run, so a finished
      // run changes every number on it. The rules come from a YAML file and a run does not
      // touch them.
      events.on(ingestEvents.finished).pipe(map(() => configEvents.sourcesOpened())),
      events.on(configEvents.rulesOpened).pipe(
        exhaustMap(() =>
          api.rules().pipe(
            map((rules) => configEvents.rulesLoaded(rules)),
            catchError(() => of(configEvents.failed('error.rulesLoad'))),
          ),
        ),
      ),
    ];
  }),
);
