import {inject} from '@angular/core';
import {signalStore, withState} from '@ngrx/signals';
import {Events, on, withEventHandlers, withReducer} from '@ngrx/signals/events';
import {filter, map, mergeMap, takeUntil, timer} from 'rxjs';
import {shortlistEvents} from '@core/store/shortlist.events';
import {toastEvents} from './toast.events';
import {TOAST_CAP, TOAST_LIFETIME_MS, Toast, toast} from './toast.model';

interface ToastState {
    /** Oldest first. The stack renders them in this order and the cap drops from the front. */
    toasts: readonly Toast[];
}

const initialState: ToastState = {toasts: []};

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
                        toast(
                            'success',
                            payload.offer.archivedAt === null ? 'toast.restored' : 'toast.archived',
                            {title: payload.offer.title},
                            `/shortlist/${payload.offer.id}`,
                        ),
                    ),
                ),
            ),
        ];
    }),
);
