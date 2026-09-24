import {inject} from '@angular/core';
import {signalStore, withState} from '@ngrx/signals';
import {Events, on, withEventHandlers, withReducer} from '@ngrx/signals/events';
import {catchError, exhaustMap, filter, map, of, switchMap} from 'rxjs';
import {ConfigApi} from '@core/api/config.api';
import {PromptView} from '@core/model/prompt-view';
import {RulesView} from '@core/model/rules-view';
import {SourceDetail} from '@core/model/source-detail';
import {SourceSummary} from '@core/model/source-summary';
import {WorkflowView} from '@core/model/workflow';
import {configEvents} from './config.events';
import {ingestEvents} from './ingest.events';
import {shortlistEvents} from './shortlist.events';
import {refreshEvents} from '@core/refresh/refresh.events';
import {withAppDevtools} from '@core/store/devtools';

interface ConfigState {
    sources: readonly SourceSummary[];
  /**
   * Which file defines them, and from which of the two layers. On the screen once, above
   * the table, because that is the scope the answer is true at — it used to be a badge in
   * every row, claiming per source what only ever varies per file.
   */
  sourcesFile: string;
  sourcesLayer: string;
  /**
   * The source whose panel is open, and what it was loaded for.
   *
   * The *route* decides which source is open; this is which one was fetched, the same
   * distinction `ShortlistStore.filters` makes. It is what lets a finished run refresh an
   * open panel: a run writes a new row into its history, and a hot reload may have rewritten
   * the block underneath it.
   */
  detail: SourceDetail | null;
  detailId: string | null;
  detailLoading: boolean;
  detailError: string | null;
    rules: RulesView | null;
    prompts: readonly PromptView[];
  /**
   * The pipeline as the rules screen draws it: phases, stages in run order, and every key
   * filed under the stage that reads it. Null until the first answer, which the screen tells
   * apart from a workflow with nothing in it.
   */
  workflow: WorkflowView | null;
  /**
   * One pair per screen, and this used to be one pair for both.
   *
   * Shared, an error raised while the rules were loading appeared on the sources screen
   * after a navigation, and a failed detail fetch would blank the list beside it — the exact
   * defect `ShortlistStore` paid for and documents.
   */
  sourcesLoading: boolean;
  sourcesError: string | null;
  rulesLoading: boolean;
  rulesError: string | null;
}

const initialState: ConfigState = {
    sources: [],
  sourcesFile: '',
  sourcesLayer: '',
  detail: null,
  detailId: null,
  detailLoading: false,
  detailError: null,
    rules: null,
    prompts: [],
  workflow: null,
  sourcesLoading: false,
  sourcesError: null,
  rulesLoading: false,
  rulesError: null,
};

/**
 * The configuration as the Sources and Rules screens read it.
 *
 * Two screens in one store because they answer one question from one place: the sources
 * list is the configuration joined with what each source last did, and the rules are the
 * same snapshot the pipeline is running on. Splitting them would mean two stores reloading
 * the same thing after a run.
 *
 * `sources` reloads when a run finishes and `rules` does not. A run changes every number on
 * the sources screen and touches no YAML file. `prompts` load with the rules, because they are
 * rendered out of the same snapshot and answer the other half of the same question.
 */
/**
 * How many of a source's runs the panel asks for. The panel says how many it is showing, so
 * the list never pretends to be the whole history — `source_run` is append-only and a nightly
 * pass for a year is 365 rows.
 */
const RUNS = 30;

export const ConfigStore = signalStore(
    {providedIn: 'root'},
    withState(initialState),
    withAppDevtools('config'),
    withReducer(
      on(configEvents.sourcesOpened, () => ({sourcesLoading: true, sourcesError: null})),
      on(configEvents.sourcesLoaded, ({payload}) => ({
        sources: payload.sources,
        sourcesFile: payload.file,
        sourcesLayer: payload.layer,
        sourcesLoading: false,
      })),
      // The id is kept before the answer arrives, so a refresh mid-flight asks for the same
      // source rather than for nothing.
      on(configEvents.sourceOpened, ({payload}) => ({
        detailId: payload,
        detailLoading: true,
        detailError: null,
        })),
      on(configEvents.sourceLoaded, ({payload}) => ({detail: payload, detailLoading: false})),
      on(configEvents.sourceFailed, ({payload}) => ({detailError: payload, detailLoading: false})),
      // Dropped rather than kept: the panel is gone, and a stale block waiting behind it is
      // what the next source would open with for a frame.
      on(configEvents.sourceClosed, () => ({
        detail: null,
        detailId: null,
        detailLoading: false,
        detailError: null,
      })),
      on(configEvents.rulesOpened, () => ({rulesLoading: true, rulesError: null})),
      on(configEvents.rulesLoaded, ({payload}) => ({rules: payload, rulesLoading: false})),
        on(configEvents.promptsLoaded, ({payload}) => ({prompts: payload})),
      on(configEvents.workflowLoaded, ({payload}) => ({workflow: payload})),
      on(configEvents.sourcesFailed, ({payload}) => ({sourcesError: payload, sourcesLoading: false})),
      on(configEvents.rulesFailed, ({payload}) => ({rulesError: payload, rulesLoading: false})),
    ),
  withEventHandlers((store) => {
        const events = inject(Events);
        const api = inject(ConfigApi);

        return [
            events.on(configEvents.sourcesOpened).pipe(
                exhaustMap(() =>
                    api.sources().pipe(
                        map((sources) => configEvents.sourcesLoaded(sources)),
                      catchError(() => of(configEvents.sourcesFailed('error.sourcesLoad'))),
                    ),
                ),
            ),
            // The sources screen counts documents, offers and survivors per run, so a finished
            // run changes every number on it. The rules come from a YAML file and a run does not
            // touch them.
            events.on(ingestEvents.finished).pipe(map(() => configEvents.sourcesOpened())),
          events.on(refreshEvents.requested).pipe(map(() => configEvents.sourcesOpened())),
          // An open panel follows the same two signals, and it has to: a run writes a new row
          // into that source's history, and the configuration is hot-reloadable, so the block
          // beside it may have been rewritten since it was fetched. Which source is open is
          // the route's answer; which one is *loaded* is this store's, and that is the one a
          // refresh can act on.
          events.on(ingestEvents.finished, refreshEvents.requested).pipe(
            map(() => store.detailId()),
            filter((id): id is string => id !== null),
            map((id) => configEvents.sourceOpened(id)),
          ),
          // `switchMap`, unlike the list's `exhaustMap`: opening a second source while the
          // first is in flight must answer with the second, and the first answer is then
          // worth nothing. The list has no such second question — it is always the same one.
          events.on(configEvents.sourceOpened).pipe(
            switchMap((event) =>
              api.source(event.payload, RUNS).pipe(
                map((detail) => configEvents.sourceLoaded(detail)),
                catchError(() => of(configEvents.sourceFailed('error.sourceLoad'))),
              ),
            ),
          ),
            events.on(configEvents.rulesOpened).pipe(
                exhaustMap(() =>
                    api.rules().pipe(
                        map((rules) => configEvents.rulesLoaded(rules)),
                      catchError(() => of(configEvents.rulesFailed('error.rulesLoad'))),
                    ),
                ),
            ),
            // A second request rather than one payload: the prompts are rendered per call from
            // the same snapshot, and a screen that has the rules is useful without them.
            events.on(configEvents.rulesOpened).pipe(
                exhaustMap(() =>
                    api.prompts().pipe(
                        map((prompts) => configEvents.promptsLoaded(prompts)),
                      catchError(() => of(configEvents.rulesFailed('error.promptsLoad'))),
                    ),
                ),
            ),
          // The third request of the rules screen: the stages its rail lists and the keys
          // each one reads.
          events.on(configEvents.rulesOpened).pipe(
            exhaustMap(() =>
              api.workflow().pipe(
                map((workflow) => configEvents.workflowLoaded(workflow)),
                catchError(() => of(configEvents.rulesFailed('error.workflowLoad'))),
              ),
            ),
          ),
          // The rail's counts are what the last run left and what the filter removed. Both
          // already have a store and a request, the dashboard's, so the rules screen asks
          // those stores rather than fetching the same rows a second way. The stores answer
          // only once something has injected them, which the screen does to read the counts.
          events.on(configEvents.rulesOpened).pipe(map(() => ingestEvents.lastRunRequested())),
          events.on(configEvents.rulesOpened).pipe(map(() => shortlistEvents.funnelOpened())),
        ];
    }),
);
