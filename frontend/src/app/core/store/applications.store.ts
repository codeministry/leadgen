import {computed, inject} from '@angular/core';
import {signalStore, withComputed, withState} from '@ngrx/signals';
import {Events, on, withEventHandlers, withReducer} from '@ngrx/signals/events';
import {catchError, concatMap, exhaustMap, filter, forkJoin, map, of} from 'rxjs';
import {ApplicationsApi} from '@core/api/applications.api';
import {
  ApplicationEvent,
  ApplicationStatus,
  ApplicationView,
  PipelineLane,
  statusLabel,
} from '@core/model/application';
import {applicationEvents} from './applications.events';
import {ingestEvents} from './ingest.events';
import {refreshEvents} from '@core/refresh/refresh.events';
import {shortlistEvents} from './shortlist.events';

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
    {providedIn: 'root'},
    withState(initialState),
    withComputed(({applications, lanes}) => ({
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
                lane.states.map((status) => ({value: status, label: statusLabel(status)})),
            ),
        ),
        followUpsDue: computed(
            () => applications().filter((application) => application.followUpDue).length,
        ),
    })),
    withReducer(
        on(applicationEvents.opened, () => ({loading: true, error: null})),
        on(applicationEvents.loaded, ({payload}) => ({
            applications: payload.applications,
            lanes: payload.lanes,
            loading: false,
        })),
        on(applicationEvents.failed, ({payload}) => ({error: payload, loading: false})),
        on(applicationEvents.changed, ({payload}) => ({saving: payload.id, error: null})),
        on(applicationEvents.updated, ({payload}, state) => ({
            applications: state.applications.map((application) =>
                application.id === payload.id ? payload : application,
            ),
            saving: null,
        })),
        on(applicationEvents.changeFailed, ({payload}) => ({error: payload, saving: null})),
        on(applicationEvents.historyLoaded, ({payload}, state) => ({
            history: {...state.history, [payload.id]: payload.events},
        })),
      /*
       * The board shows the working list, and an archived offer is not on it. The server
       * already knows that — `ApplicationService.BOARD` carries the same predicate the
       * shortlist does — but the archive button sits in the offer detail, which writes
       * through `ShortlistStore`. Without these two the card stayed until a reload, on the
       * board and in the dashboard's follow-up count with it.
       *
       * Dropped rather than refetched: it is the same answer the shortlist's own reducer
       * gives, it costs no request, and it cannot race a board load that is already in
       * flight — `applicationEvents.opened` is `exhaustMap`, so a refetch dispatched during
       * one would be swallowed and the board would stay stale anyway.
       */
      on(shortlistEvents.archived, ({payload}, state) =>
        payload.offer.archivedAt === null
          ? {}
          : {
            applications: state.applications.filter(
              (application) => application.offerId !== payload.offer.id,
            ),
          },
      ),
      // The plural never restores, so there is no second branch here.
      on(shortlistEvents.bulkArchived, ({payload}, state) => ({
        applications: state.applications.filter(
          (application) => !payload.ids.includes(application.offerId),
        ),
      })),
    ),
    withEventHandlers(() => {
        const events = inject(Events);
        const api = inject(ApplicationsApi);

        return [
            events.on(applicationEvents.opened).pipe(
                exhaustMap(() =>
                    forkJoin({applications: api.board(), lanes: api.lanes()}).pipe(
                        map((payload) => applicationEvents.loaded(payload)),
                        catchError(() => of(applicationEvents.failed('error.boardLoad'))),
                    ),
                ),
            ),
            // Serialised rather than merged: two changes to the same card in quick succession
            // must land in the order they were made, and the second answer is the one that wins.
            // The last thing a run does is build a package, and an application opens with it.
            // The board would otherwise not show the work the run just created until a reload.
            events.on(ingestEvents.finished).pipe(map(() => applicationEvents.opened())),
          // And on anything else that says the data moved. The board is a small list and
          // re-reading it costs nothing a reader can feel — unlike the shortlist, which is
          // paged and would lose the reader's place.
          events.on(refreshEvents.requested).pipe(map(() => applicationEvents.opened())),
          /*
           * A restore is the one case the reducer above cannot answer: the card belongs
           * back on the board and this store has no row to put there, because dropping it
           * threw the row away. So the board is read again. Rare enough to cost nothing,
           * and the alternative — keeping archived rows around in case one comes back — is
           * a second idea of what the board is.
           */
          events
            .on(shortlistEvents.archived)
            .pipe(
              filter(({payload}) => payload.offer.archivedAt === null),
              map(() => applicationEvents.opened()),
            ),
            events.on(applicationEvents.changed).pipe(
                concatMap(({payload}) =>
                    api.update(payload.id, payload.update).pipe(
                        map((view) => applicationEvents.updated(view)),
                        catchError(() =>
                            of(applicationEvents.changeFailed('The status was not saved. Nothing changed.')),
                        ),
                    ),
                ),
            ),
            events.on(applicationEvents.historyRequested).pipe(
                concatMap(({payload}) =>
                    api.history(payload).pipe(
                        map((history) => applicationEvents.historyLoaded({id: payload, events: history})),
                        catchError(() => of(applicationEvents.failed('error.historyLoad'))),
                    ),
                ),
            ),
        ];
    }),
);
