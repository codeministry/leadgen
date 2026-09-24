import {DOCUMENT, inject, InjectionToken} from '@angular/core';
import {SwUpdate, VersionReadyEvent} from '@angular/service-worker';
import {signalStore, withState} from '@ngrx/signals';
import {Events, on, withEventHandlers, withReducer} from '@ngrx/signals/events';
import {catchError, distinctUntilChanged, exhaustMap, filter, from, interval, map, mergeMap, of, tap, timeout} from 'rxjs';
import {withAppDevtools} from '@core/store/devtools';
import {updateEvents} from './update.events';

/**
 * The reload behind the toast's button, as a seam: `document.location.reload()` in the
 * browser, a spy in the spec. The one thing in this store a test could not otherwise call.
 */
export const RELOAD = new InjectionToken<() => void>('leadgen.reload', {
    providedIn: 'root',
    factory: () => {
        const document = inject(DOCUMENT);
        return () => document.location.reload();
    },
});

/**
 * How often an open tab asks the worker whether a deploy has landed.
 *
 * <p>The worker checks by itself on every navigation, and an installed window that is
 * never navigated away from would otherwise run yesterday's bundle until it is closed.
 * An hour because a deploy is a daily event here at most, and the check is one small
 * request for `ngsw.json`. It runs only while the tab is visible: a hidden tab has nobody
 * to show the toast to, and the hour it comes back is soon enough.
 */
export const UPDATE_CHECK_INTERVAL_MS = 60 * 60 * 1000;

/**
 * How long an activation may take before the tab reloads without it.
 *
 * <p>The toast is gone the moment the button is pressed, so an activation that never
 * settles would leave no toast, no reload and no message. Ten seconds is far past what the
 * worker needs to swap a client and short enough to still read as "the button worked";
 * after it the reload goes ahead, the same as when the activation is refused.
 */
export const ACTIVATE_TIMEOUT_MS = 10_000;

interface UpdateState {
    /** The hash of the version the worker holds ready, or null while it serves the newest. */
    ready: string | null;
}

const initialState: UpdateState = {ready: null};

/**
 * Notices a new version and, on the person's word, brings it in (ISC-331).
 *
 * <p>The worker's `versionUpdates` stream is the I/O; `VERSION_READY` becomes the store's
 * own `versionReady`, once per hash, and the toast store turns that into the one toast with
 * an action. The action's event, `activate`, comes back here: the update is activated and
 * the tab reloads. The reload is the documented way to a new version and would bring it
 * alone; the activation first is what makes the reload land on it for certain rather than
 * on the next navigation. Closing the toast dispatches nothing here, so the running version
 * simply stays. The worker's `unrecoverable` stream — the running version lost a bundle it
 * needs — reloads through the same seam without asking: that page is already unusable.
 *
 * <p>Under a disabled worker — the dev server, the test environment, a browser without
 * service workers — `isEnabled` is false and the store subscribes to nothing at all: no
 * stream, no timer. Instantiated at startup by `provideAppInitializer` in `app.config.ts`,
 * because nothing reads it: like `RefreshStore`, it exists to watch.
 *
 * <p>Same shape as `core/store/status.store.ts`: `withReducer` for the one transition,
 * `withEventHandlers` for the I/O, the interval an RxJS `interval` rather than any zone API.
 */
export const UpdateStore = signalStore(
    {providedIn: 'root'},
    withState(initialState),
    withAppDevtools('update'),
    withReducer(on(updateEvents.versionReady, ({payload}) => ({ready: payload}))),
    withEventHandlers(() => {
        const sw = inject(SwUpdate);
        const events = inject(Events);
        const document = inject(DOCUMENT);
        const reload = inject(RELOAD);

        if (!sw.isEnabled) {
            return [];
        }

        return [
            // Once per hash, keyed in the stream: the worker announces a version when its
            // download completes, and a check that finds nothing newer says so with another
            // event type. A version that comes back after a rollback is news again.
            sw.versionUpdates.pipe(
                filter((event): event is VersionReadyEvent => event.type === 'VERSION_READY'),
                map((event) => event.latestVersion.hash),
                distinctUntilChanged(),
                map((hash) => updateEvents.versionReady(hash)),
            ),
            // Activate, then reload, and reload even when the activation is refused or never
            // settles: the reload is what brings the version, the activation only makes it
            // certain. A second click while the first is in flight is dropped; the reload ends
            // both.
            events.on(updateEvents.activate).pipe(
                exhaustMap(() =>
                    from(sw.activateUpdate()).pipe(
                        timeout(ACTIVATE_TIMEOUT_MS),
                        catchError(() => of(false)),
                    ),
                ),
                tap(() => reload()),
            ),
            // The worker gave up on the running version: a hashed bundle it needed is gone
            // (nginx answers a stale hash with 404, and the worker then drops the version) and
            // the window is broken until a reload. No toast, because there is nothing to offer
            // a person on a page that no longer works; the reload is the repair, taken at once.
            sw.unrecoverable.pipe(tap(() => reload())),
            // The hourly ask, skipped while hidden. `mergeMap` rather than `exhaustMap`: a check
            // that never settles must not hold every later hour hostage, and `SwUpdate` already
            // folds a check into one still in flight. A refused check is dropped, since the next
            // hour asks again and there is no one to tell.
            interval(UPDATE_CHECK_INTERVAL_MS).pipe(
                filter(() => document.visibilityState === 'visible'),
                mergeMap(() => from(sw.checkForUpdate()).pipe(catchError(() => of(false)))),
            ),
        ];
    }),
);
