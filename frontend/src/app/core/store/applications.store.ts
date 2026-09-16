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
    /**
     * The lane split by state, because a lane is not a drop target: four of the five hold
     * more than one, and `closed` begins at `WON` — dropping a card on the lane would mark
     * it won. The lane's own list stays beside this one; the count in the header reads it.
     */
    readonly groups: readonly StatusGroup[];
}

/** One state of a lane, and what stands in it. The board's drop target. */
export interface StatusGroup {
    readonly status: ApplicationStatus;
    readonly label: string;
    readonly applications: readonly ApplicationView[];
}

export interface StatusChoice {
    readonly value: ApplicationStatus;
    readonly label: string;
    /** The lane the state belongs to, which the picker renders as an `<optgroup>`. */
    readonly group: string;
}

interface ApplicationsState {
    applications: readonly ApplicationView[];
    lanes: readonly PipelineLane[];
    /** Keyed by application id, and only for the ones actually looked at. */
    history: Record<number, readonly ApplicationEvent[]>;
    /** The application currently in flight, so one card can say "saving" and the rest cannot. */
    saving: number | null;
    /**
     * The rows as they stood before an in-flight change, keyed by id — what a failed write
     * is put back to. Keyed rather than a single slot because the writes are `concatMap`:
     * a second change queues behind the first and would otherwise overwrite its undo.
     */
    pending: Record<number, ApplicationView>;
    loading: boolean;
    error: string | null;
}

/** The record without one key. A change is finished either way, so the undo goes with it. */
function without(
    pending: Record<number, ApplicationView>,
    id: number,
): Record<number, ApplicationView> {
    // A copy and a `delete` rather than a rest destructure: the binding the destructure
    // needs for the removed key is never read, and `no-unused-vars` has no exception for it.
    const rest = {...pending};
    delete rest[id];
    return rest;
}

const initialState: ApplicationsState = {
    applications: [],
    lanes: [],
    history: {},
    saving: null,
    pending: {},
    loading: false,
    error: null,
};

export const ApplicationsStore = signalStore(
    {providedIn: 'root'},
    withState(initialState),
    withComputed(({applications, lanes}) => ({
        columns: computed<readonly BoardColumn[]>(() =>
            lanes().map((lane) => {
                const inLane = applications().filter((application) =>
                    lane.states.includes(application.status),
                );
                return {
                    lane,
                    applications: inLane,
                    groups: lane.states.map((status) => ({
                        status,
                        label: statusLabel(status),
                        applications: inLane.filter((application) => application.status === status),
                    })),
                };
            }),
        ),
        /**
         * The eleven states in the order the lanes put them, which is the order the usual
         * path runs, each under its lane's name. Derived from the server's answer rather
         * than listed again here — the grouping the board draws and the grouping the picker
         * offers are then the same decision, made once, in the enum.
         */
        statusChoices: computed<readonly StatusChoice[]>(() =>
            lanes().flatMap((lane) =>
                lane.states.map((status) => ({
                    value: status,
                    label: statusLabel(status),
                    group: lane.label,
                })),
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
      /*
       * The card moves the moment the operator lets go of it, and the row it moved from is
       * kept. A drag whose card stays put until the answer is back reads as a drag that did
       * not take, and the answer is a round trip away.
       *
       * The status alone is patched, never the rest of the update: the server dates a send
       * itself and drops the follow-up on closing, so anything else written here would be a
       * guess standing next to the real row until `updated` replaces it.
       */
      on(applicationEvents.changed, ({payload}, state) => {
        const before = state.applications.find((application) => application.id === payload.id);
        if (before === undefined) {
          return {saving: payload.id, error: null};
        }
        return {
          saving: payload.id,
          error: null,
          pending: {...state.pending, [payload.id]: before},
          applications: state.applications.map((application) =>
            application.id === payload.id
              ? {...application, status: payload.update.status}
              : application,
          ),
        };
      }),
      // And the server's answer replaces the row, never what was asked for.
      on(applicationEvents.updated, ({payload}, state) => ({
        applications: state.applications.map((application) =>
          application.id === payload.id ? payload : application,
        ),
        pending: without(state.pending, payload.id),
        saving: null,
      })),
      on(applicationEvents.changeFailed, ({payload}, state) => {
        // Annotated, because `noUncheckedIndexedAccess` is off and the lookup is otherwise
        // typed as if every id were in there — which would make the guard below a type error.
        const before: ApplicationView | undefined = state.pending[payload.id];
        return {
          error: payload.message,
          saving: null,
          pending: without(state.pending, payload.id),
          applications:
            before === undefined
              ? state.applications
              : state.applications.map((application) =>
                application.id === payload.id ? before : application,
              ),
        };
      }),
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
                            of(
                                applicationEvents.changeFailed({
                                    id: payload.id,
                                    message: 'error.statusSave',
                                }),
                            ),
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
