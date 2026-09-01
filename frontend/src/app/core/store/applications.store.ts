import { computed, inject } from '@angular/core';
import { signalStore, withComputed, withState } from '@ngrx/signals';
import { Events, on, withEventHandlers, withReducer } from '@ngrx/signals/events';
import { catchError, concatMap, exhaustMap, forkJoin, map, of } from 'rxjs';
import { ApplicationsApi } from '@core/api/applications.api';
import {
  ApplicationEvent,
  ApplicationStatus,
  ApplicationView,
  PipelineLane,
  statusLabel,
} from '@core/model/application';
import { applicationEvents } from './applications.events';
import { ingestEvents } from './ingest.events';

export interface BoardColumn {
  readonly lane: PipelineLane;
  readonly applications: readonly ApplicationView[];
}

export interface StatusChoice {
  readonly value: ApplicationStatus;
  readonly label: string;
}

interface ApplicationsState {
  applications: readonly ApplicationView[];
  lanes: readonly PipelineLane[];
  /** Keyed by application id, and only for the ones actually looked at. */
  history: Record<number, readonly ApplicationEvent[]>;
  /** The application currently in flight, so one card can say "saving" and the rest cannot. */
  saving: number | null;
  loading: boolean;
  error: string | null;
}

const initialState: ApplicationsState = {
  applications: [],
  lanes: [],
  history: {},
  saving: null,
  loading: false,
  error: null,
};

export const ApplicationsStore = signalStore(
  { providedIn: 'root' },
  withState(initialState),
  withComputed(({ applications, lanes }) => ({
    columns: computed<readonly BoardColumn[]>(() =>
      lanes().map((lane) => ({
        lane,
        applications: applications().filter((application) =>
          lane.states.includes(application.status),
        ),
      })),
    ),
    /**
     * The eleven states in the order the lanes put them, which is the order the usual
     * path runs. Derived from the server's answer rather than listed again here.
     */
    statusChoices: computed<readonly StatusChoice[]>(() =>
      lanes().flatMap((lane) =>
        lane.states.map((status) => ({ value: status, label: statusLabel(status) })),
      ),
    ),
    followUpsDue: computed(
      () => applications().filter((application) => application.followUpDue).length,
    ),
  })),
  withReducer(
    on(applicationEvents.opened, () => ({ loading: true, error: null })),
    on(applicationEvents.loaded, ({ payload }) => ({
      applications: payload.applications,
      lanes: payload.lanes,
      loading: false,
    })),
    on(applicationEvents.failed, ({ payload }) => ({ error: payload, loading: false })),
    on(applicationEvents.changed, ({ payload }) => ({ saving: payload.id, error: null })),
    on(applicationEvents.updated, ({ payload }, state) => ({
      applications: state.applications.map((application) =>
        application.id === payload.id ? payload : application,
      ),
      saving: null,
    })),
    on(applicationEvents.changeFailed, ({ payload }) => ({ error: payload, saving: null })),
    on(applicationEvents.historyLoaded, ({ payload }, state) => ({
      history: { ...state.history, [payload.id]: payload.events },
    })),
  ),
  withEventHandlers(() => {
    const events = inject(Events);
    const api = inject(ApplicationsApi);

    return [
      events.on(applicationEvents.opened).pipe(
        exhaustMap(() =>
          forkJoin({ applications: api.board(), lanes: api.lanes() }).pipe(
            map((payload) => applicationEvents.loaded(payload)),
            catchError(() => of(applicationEvents.failed('The board did not load.'))),
          ),
        ),
      ),
      // Serialised rather than merged: two changes to the same card in quick succession
      // must land in the order they were made, and the second answer is the one that wins.
      // The last thing a run does is build a package, and an application opens with it.
      // The board would otherwise not show the work the run just created until a reload.
      events.on(ingestEvents.finished).pipe(map(() => applicationEvents.opened())),
      events.on(applicationEvents.changed).pipe(
        concatMap(({ payload }) =>
          api.update(payload.id, payload.update).pipe(
            map((view) => applicationEvents.updated(view)),
            catchError(() =>
              of(applicationEvents.changeFailed('The status was not saved. Nothing changed.')),
            ),
          ),
        ),
      ),
      events.on(applicationEvents.historyRequested).pipe(
        concatMap(({ payload }) =>
          api.history(payload).pipe(
            map((history) => applicationEvents.historyLoaded({ id: payload, events: history })),
            catchError(() => of(applicationEvents.failed('The history did not load.'))),
          ),
        ),
      ),
    ];
  }),
);
