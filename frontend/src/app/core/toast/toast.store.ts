import {inject} from '@angular/core';
import {signalStore, withState} from '@ngrx/signals';
import {Events, on, withEventHandlers, withReducer} from '@ngrx/signals/events';
import {distinctUntilChanged, distinctUntilKeyChanged, filter, map, merge, mergeMap, switchMap, take, takeUntil, timer} from 'rxjs';
import {ApplicationStatus, statusLabel} from '@core/model/application';
import {LastRunView} from '@core/model/last-run';
import {refreshEvents} from '@core/refresh/refresh.events';
import {applicationEvents} from '@core/store/applications.events';
import {ingestEvents} from '@core/store/ingest.events';
import {manualEvents} from '@core/store/manual.events';
import {shortlistEvents} from '@core/store/shortlist.events';
import {toastEvents} from './toast.events';
import {TOAST_CAP, TOAST_LIFETIME_MS, Toast, toast} from './toast.model';

interface ToastState {
    /** Oldest first. The stack renders them in this order and the cap drops from the front. */
    toasts: readonly Toast[];
}

const initialState: ToastState = {toasts: []};

/**
 * The states that close an application against us. A move into one of them is "taken
 * away" and takes the warning tone; every other move, WON included, is forward and green.
 */
const CLOSED_AGAINST_US: ReadonlySet<ApplicationStatus> = new Set<ApplicationStatus>(['LOST', 'REJECTED', 'EXPIRED']);

/**
 * The one place a domain event becomes a message.
 *
 * <p>It listens to the answer events of the other stores — what the server wrote, never
 * what was asked for — and raises one toast per answer. That is what lets the detail and
 * the card produce the same line for one archive without either of them knowing a toast
 * exists, and what makes "a refused move raises none" free: `updated` only fires when the
 * server said yes, and the failure events are simply not subscribed to.
 *
 * <p>The timer lives here rather than in the stack, so the lifetime is one rule in one
 * place and a spec can run it under fake timers without rendering anything.
 */
export const ToastStore = signalStore(
    {providedIn: 'root'},
    withState(initialState),
    withReducer(
        // The cap drops the oldest, never refuses the newest: the newest is the one that
        // just happened, and the one the person is most likely looking for.
        on(toastEvents.raised, ({payload}, state) => ({
            toasts: [...state.toasts, payload].slice(-TOAST_CAP),
        })),
        on(toastEvents.dismissed, toastEvents.expired, ({payload}, state) => ({
            toasts: state.toasts.filter((standing) => standing.id !== payload),
        })),
    ),
    withEventHandlers(() => {
        const events = inject(Events);

        /** Whichever of the two carries this id: the timer's own end, or the button. */
        const gone = (id: number) =>
            events
                .on(toastEvents.held, toastEvents.dismissed, toastEvents.expired)
                .pipe(filter(({payload}) => payload === id));

        return [
            /*
             * One timer per raise and one per release, each cancelled by a hold, a dismiss
             * or its own expiry. A release starts a fresh full lifetime rather than resuming
             * the remainder: the person just read it, and a line that vanishes the instant
             * the pointer leaves is the thing the hold exists to prevent.
             */
            events.on(toastEvents.raised, toastEvents.released).pipe(
                map(({payload}) => (typeof payload === 'number' ? payload : payload.id)),
                mergeMap((id) =>
                    timer(TOAST_LIFETIME_MS).pipe(
                        takeUntil(gone(id)),
                        map(() => toastEvents.expired(id)),
                    ),
                ),
            ),

            // ── The mappings. One per answer event; the key names the catalog line. ──

            // Raised from the answer, so whichever screen wrote it produces the same toast.
            // The direction is read off the row the server returned, not off the request.
            events.on(shortlistEvents.archived).pipe(
                map(({payload}) =>
                    toastEvents.raised(
                        payload.offer.archivedAt === null
                            ? toast('success', 'toast.restored', {title: payload.offer.title}, `/shortlist/${payload.offer.id}`)
                            : toast('warning', 'toast.archived', {title: payload.offer.title}, `/shortlist/${payload.offer.id}`),
                    ),
                ),
            ),
            // The count the server wrote, never the count that was asked for: an id that
            // named no row was not archived, and the sentence must not say it was.
            events.on(shortlistEvents.bulkArchived).pipe(
                map(({payload}) =>
                    toastEvents.raised(toast('warning', 'toast.countArchived', {count: payload.archived})),
                ),
            ),
            // From `updated`, the row the server returned, and never from the optimistic
            // `changed`: the board moves the card before the answer is back, and a refused
            // move puts it back. A toast for that move would confirm what did not happen.
            // Amber for a move that closes the application against us, green for every other.
            events.on(applicationEvents.updated).pipe(
                map(({payload}) =>
                    toastEvents.raised(
                        toast(
                            CLOSED_AGAINST_US.has(payload.status) ? 'warning' : 'success',
                            'toast.statusChanged',
                            {title: payload.title, state: statusLabel(payload.status)},
                            `/pipeline/${payload.id}`,
                        ),
                    ),
                ),
            ),
            // The score the server stored. Null when nothing judged it — the pipeline runs
            // without a model — and that is a different sentence, not a missing number.
            events.on(shortlistEvents.rescored).pipe(
                map(({payload}) =>
                    toastEvents.raised(
                        payload.score.value === null
                            ? toast('info', 'toast.rescoredUnscored', {title: payload.offer.title}, `/shortlist/${payload.offer.id}`)
                            : toast(
                                'success',
                                'toast.rescored',
                                {title: payload.offer.title, score: payload.score.value},
                                `/shortlist/${payload.offer.id}`,
                            ),
                    ),
                ),
            ),
            /*
             * A run beginning, from the heartbeat's first sight of it and never from
             * `requested`: the heartbeat sees a click here and a CronJob elsewhere alike, and
             * `IngestStore` asks it at once on a click. Keyed on the run id through the
             * stream itself rather than through state, because the order in which a reducer
             * and a handler see one event is not something to depend on. The null between two
             * runs is what lets a new id through; the same id on every beat is one toast.
             */
            events.on(ingestEvents.currentLoaded).pipe(
                map(({payload}) => payload?.id ?? null),
                distinctUntilChanged(),
                filter((id): id is number => id !== null),
                map(() => toastEvents.raised(toast('info', 'toast.runStarted', undefined, '/dashboard'))),
            ),
            /*
             * A run ending, from two paths that both fire for the operator's own run: the
             * report the request handed back, and the recorded last run that `run-ended`
             * reads back a moment later. Both carry `finishedAt`, so the second is the same
             * key as the first and is dropped. A last run read for any other reason — the
             * store's own startup ask, a tab coming back — names no ending and raises nothing:
             * only a `run-ended` opens the window, and only the next answer closes it.
             *
             * A run that stopped in a stage is only ever seen on the second path — its request
             * answered 500, so `finished` never fired — and says where it stopped rather than
             * reporting counts that end there. Amber: something was taken away, the run.
             */
            merge(
                events.on(ingestEvents.finished).pipe(
                    map(({payload}) => ({
                        key: payload.finishedAt,
                        written: payload.written,
                        shortlisted: payload.scored.shortlisted,
                        failedIn: null as string | null,
                    })),
                ),
                events.on(refreshEvents.requested).pipe(
                    filter(({payload}) => payload === 'run-ended'),
                    switchMap(() => events.on(ingestEvents.lastRunLoaded).pipe(take(1))),
                    map(({payload}) => payload),
                    filter((run): run is LastRunView => run !== null),
                    map((run) => ({
                        key: run.finishedAt,
                        written: run.written,
                        shortlisted: run.shortlisted,
                        failedIn: run.status === 'FAILED' ? (run.stages?.at(-1)?.stage ?? '?') : null,
                    })),
                ),
            ).pipe(
                distinctUntilKeyChanged('key'),
                map(({written, shortlisted, failedIn}) =>
                    toastEvents.raised(
                        failedIn === null
                            ? toast('info', 'toast.runFinished', {written, shortlisted}, '/dashboard')
                            : toast('warning', 'toast.runFailed', {stage: failedIn}, '/dashboard'),
                    ),
                ),
            ),
            // The inbox's own answer carries the outcome, so one event names both sentences.
            events.on(manualEvents.settled).pipe(
                map(({payload}) =>
                    toastEvents.raised(
                        payload.outcome === 'confirmed'
                            ? toast('success', 'toast.documentConfirmed', {name: payload.name})
                            : toast('warning', 'toast.documentRejected', {name: payload.name}),
                    ),
                ),
            ),
        ];
    }),
);
