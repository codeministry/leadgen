import { computed, inject } from '@angular/core';
import { signalStore, withComputed, withState } from '@ngrx/signals';
import { Events, on, withEventHandlers, withReducer } from '@ngrx/signals/events';
import { catchError, concatMap, exhaustMap, map, of } from 'rxjs';
import { serverMessage } from '@core/api/server-message';
import { ManualApi } from '@core/api/manual.api';
import { PendingDocument } from '@core/model/manual-document';
import { manualEvents } from './manual.events';

interface ManualState {
  documents: readonly PendingDocument[];
  /** The document in flight, so one card is busy and the rest stay usable. */
  busy: string | null;
  uploading: boolean;
  loading: boolean;
  error: string | null;
}

const initialState: ManualState = {
  documents: [],
  busy: null,
  uploading: false,
  loading: false,
  error: null,
};

export const ManualStore = signalStore(
  { providedIn: 'root' },
  withState(initialState),
  withComputed(({ documents }) => ({
    waiting: computed(() => documents().length),
    /** How many of them the pipeline already holds under the same normalized title. */
    duplicates: computed(() => documents().filter((document) => document.duplicateOfId !== null).length),
  })),
  withReducer(
    on(manualEvents.opened, () => ({ loading: true, error: null })),
    on(manualEvents.loaded, ({ payload }) => ({ documents: payload, loading: false })),
    on(manualEvents.failed, ({ payload }) => ({
      error: payload,
      loading: false,
      uploading: false,
      busy: null,
    })),
    on(manualEvents.uploaded, () => ({ uploading: true, error: null })),
    on(manualEvents.stored, ({ payload }, state) => ({
      // Replaced rather than appended: uploading the same name twice overwrites the file,
      // and a second card for one document would be a queue that lies about its length.
      documents: [
        ...state.documents.filter((document) => document.name !== payload.name),
        payload,
      ],
      uploading: false,
    })),
    on(manualEvents.confirmed, ({ payload }) => ({ busy: payload.name, error: null })),
    on(manualEvents.rejected, ({ payload }) => ({ busy: payload, error: null })),
    on(manualEvents.settled, ({ payload }, state) => ({
      documents: state.documents.filter((document) => document.name !== payload),
      busy: null,
    })),
  ),
  withEventHandlers(() => {
    const events = inject(Events);
    const api = inject(ManualApi);

    return [
      events.on(manualEvents.opened).pipe(
        exhaustMap(() =>
          api.pending().pipe(
            map((documents) => manualEvents.loaded(documents)),
            catchError(() => of(manualEvents.failed('error.queueLoad'))),
          ),
        ),
      ),
      events.on(manualEvents.uploaded).pipe(
        concatMap(({ payload }) =>
          api.upload(payload).pipe(
            map((document) => manualEvents.stored(document)),
            // The server states the reason — a wrong extension, a file too large — and it
            // is the only useful thing to show. A generic message would hide it.
            catchError((error: { error?: unknown }) =>
              of(manualEvents.failed(serverMessage(error, 'The document was not accepted.'))),
            ),
          ),
        ),
      ),
      events.on(manualEvents.confirmed).pipe(
        concatMap(({ payload }) =>
          api.confirm(payload.name, payload.fields).pipe(
            map(() => manualEvents.settled(payload.name)),
            catchError((error: { error?: unknown }) =>
              of(manualEvents.failed(serverMessage(error, 'The document was not confirmed.'))),
            ),
          ),
        ),
      ),
      events.on(manualEvents.rejected).pipe(
        concatMap(({ payload }) =>
          api.reject(payload).pipe(
            map(() => manualEvents.settled(payload)),
            catchError(() => of(manualEvents.failed('The document was not deleted.'))),
          ),
        ),
      ),
    ];
  }),
);
