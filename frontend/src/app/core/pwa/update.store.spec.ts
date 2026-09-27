import {TestBed} from '@angular/core/testing';
import {SwUpdate, UnrecoverableStateEvent, VersionEvent} from '@angular/service-worker';
import {Dispatcher} from '@ngrx/signals/events';
import {Subject} from 'rxjs';
import {Mock} from 'vitest';
import {toastEvents} from '@core/toast/toast.events';
import {ToastStore} from '@core/toast/toast.store';
import {updateEvents} from './update.events';
import {ACTIVATE_TIMEOUT_MS, RELOAD, UPDATE_CHECK_INTERVAL_MS, UpdateStore} from './update.store';

/** Only what the store reads off the worker: the type and the hash of what is waiting. */
function ready(hash: string): VersionEvent {
    return {type: 'VERSION_READY', currentVersion: {hash: 'running'}, latestVersion: {hash}};
}

interface FakeSwUpdate {
    isEnabled: boolean;
    versionUpdates: Subject<VersionEvent>;
    unrecoverable: Subject<UnrecoverableStateEvent>;
    activateUpdate: Mock<() => Promise<boolean>>;
    checkForUpdate: Mock<() => Promise<boolean>>;
}

/** Waits out the promise chain behind an activation. Not a timer: nothing here sleeps. */
async function settle(): Promise<void> {
    for (let i = 0; i < 4; i += 1) {
        await Promise.resolve();
    }
}

/**
 * The update store (ISC-331): the worker's `VERSION_READY` becomes one toast with a reload
 * action, taking the action activates and reloads, closing the toast leaves the running
 * version alone, and a disabled worker — the dev server, every other spec — means nothing
 * at all happens. The worker is faked at `SwUpdate`, the reload at the `RELOAD` seam, and
 * the toast store is the real one, so the test reads the toast the person would.
 */
describe('UpdateStore', () => {
    let sw: FakeSwUpdate;
    let reload: Mock<() => void>;
    let store: InstanceType<typeof UpdateStore>;
    let toasts: InstanceType<typeof ToastStore>;
    let dispatcher: Dispatcher;

    function boot(enabled = true): void {
        sw = {
            isEnabled: enabled,
            versionUpdates: new Subject<VersionEvent>(),
            unrecoverable: new Subject<UnrecoverableStateEvent>(),
            activateUpdate: vi.fn(() => Promise.resolve(true)),
            checkForUpdate: vi.fn(() => Promise.resolve(false)),
        };
        reload = vi.fn();
        TestBed.configureTestingModule({
            providers: [
                {provide: SwUpdate, useValue: sw},
                {provide: RELOAD, useValue: reload},
            ],
        });
        store = TestBed.inject(UpdateStore);
        toasts = TestBed.inject(ToastStore);
        dispatcher = TestBed.inject(Dispatcher);
    }

    it('raises one toast per version, blue, with the reload as its action and no link', () => {
        boot();

        sw.versionUpdates.next({type: 'VERSION_DETECTED', version: {hash: 'a1'}});
        expect(toasts.toasts()).toEqual([]);

        sw.versionUpdates.next(ready('a1'));
        sw.versionUpdates.next(ready('a1'));
        expect(toasts.toasts().length).toBe(1);
        expect(toasts.toasts()[0]).toMatchObject({
            tone: 'info',
            key: 'toast.update.ready',
            action: {key: 'toast.update.reload', event: {type: updateEvents.activate().type}},
        });
        expect(toasts.toasts()[0].link).toBeUndefined();
        expect(store.ready()).toBe('a1');

        // A second version while the first toast still stands: the older toast describes a
        // version the worker no longer holds, so it goes and one fresh toast stands.
        const first = toasts.toasts()[0].id;
        sw.versionUpdates.next(ready('b2'));
        expect(toasts.toasts().length).toBe(1);
        expect(toasts.toasts()[0].id).not.toBe(first);
        expect(toasts.toasts()[0]).toMatchObject({key: 'toast.update.ready'});
        expect(store.ready()).toBe('b2');
    });

    it('activates the update and then reloads, once, when the action is taken', async () => {
        boot();
        sw.versionUpdates.next(ready('a1'));
        const action = toasts.toasts()[0].action;
        expect(action).toBeDefined();

        dispatcher.dispatch(action!.event);
        await settle();

        expect(sw.activateUpdate).toHaveBeenCalledTimes(1);
        expect(reload).toHaveBeenCalledTimes(1);
        expect(sw.activateUpdate.mock.invocationCallOrder[0]).toBeLessThan(reload.mock.invocationCallOrder[0]);
    });

    it('still reloads when the activation is refused: the reload is what brings the version', async () => {
        boot();
        sw.activateUpdate.mockImplementation(() => Promise.reject(new Error('no client')));
        sw.versionUpdates.next(ready('a1'));

        dispatcher.dispatch(toasts.toasts()[0].action!.event);
        await settle();

        expect(reload).toHaveBeenCalledTimes(1);
    });

    it('reloads anyway once an activation that never settles has had its time', () => {
        vi.useFakeTimers();
        boot();
        sw.activateUpdate.mockImplementation(() => new Promise<boolean>(() => undefined));
        sw.versionUpdates.next(ready('a1'));

        dispatcher.dispatch(toasts.toasts()[0].action!.event);
        vi.advanceTimersByTime(ACTIVATE_TIMEOUT_MS - 1);
        expect(reload).not.toHaveBeenCalled();

        vi.advanceTimersByTime(1);
        expect(sw.activateUpdate).toHaveBeenCalledTimes(1);
        expect(reload).toHaveBeenCalledTimes(1);
        vi.useRealTimers();
    });

    it('leaves the running version alone when the toast is closed', async () => {
        boot();
        sw.versionUpdates.next(ready('a1'));

        dispatcher.dispatch(toastEvents.dismissed(toasts.toasts()[0].id));
        await settle();

        expect(toasts.toasts()).toEqual([]);
        expect(sw.activateUpdate).not.toHaveBeenCalled();
        expect(reload).not.toHaveBeenCalled();
        expect(store.ready()).toBe('a1');
    });

    it('reloads at once when the worker declares the running version unrecoverable, with no toast', () => {
        // A stale hashed bundle answers 404 under the nginx cache policy; the worker then
        // drops the version and the window is broken until a reload. Nothing to offer here.
        boot();

        sw.unrecoverable.next({type: 'UNRECOVERABLE_STATE', reason: 'Hash mismatch (cacheBustedFetchFromNetwork)'});

        expect(reload).toHaveBeenCalledTimes(1);
        expect(sw.activateUpdate).not.toHaveBeenCalled();
        expect(toasts.toasts()).toEqual([]);
    });

    it('does nothing at all while the worker is not enabled', () => {
        vi.useFakeTimers();
        boot(false);

        expect(sw.versionUpdates.observed).toBe(false);
        expect(sw.unrecoverable.observed).toBe(false);
        sw.versionUpdates.next(ready('a1'));
        vi.advanceTimersByTime(UPDATE_CHECK_INTERVAL_MS * 2);

        expect(toasts.toasts()).toEqual([]);
        expect(store.ready()).toBeNull();
        expect(sw.checkForUpdate).not.toHaveBeenCalled();
        vi.useRealTimers();
    });

    describe('the hourly check', () => {
        let visibility: DocumentVisibilityState;

        beforeEach(() => {
            vi.useFakeTimers();
            visibility = 'visible';
            Object.defineProperty(document, 'visibilityState', {configurable: true, get: () => visibility});
        });

        afterEach(() => {
            // Back to the prototype's own getter, so no other spec inherits a hidden document.
            delete (document as unknown as {visibilityState?: unknown}).visibilityState;
            vi.useRealTimers();
        });

        it('asks the worker every hour while the tab is visible, and skips the hour it is hidden', () => {
            boot();
            expect(sw.checkForUpdate).not.toHaveBeenCalled();

            vi.advanceTimersByTime(UPDATE_CHECK_INTERVAL_MS);
            expect(sw.checkForUpdate).toHaveBeenCalledTimes(1);

            visibility = 'hidden';
            vi.advanceTimersByTime(UPDATE_CHECK_INTERVAL_MS);
            expect(sw.checkForUpdate).toHaveBeenCalledTimes(1);

            visibility = 'visible';
            vi.advanceTimersByTime(UPDATE_CHECK_INTERVAL_MS);
            expect(sw.checkForUpdate).toHaveBeenCalledTimes(2);
        });
    });
});
