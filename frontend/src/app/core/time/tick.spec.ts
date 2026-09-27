import {TestBed} from '@angular/core/testing';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';
import {injectTick, TICK_INTERVAL_MS} from './tick';

/**
 * The shared clock (ISC-412's timing half): it ticks once a second under fake timers and never
 * more often, every consumer shares the one interval rather than starting its own, and the
 * interval stops when its injector goes — proven by spying on `setInterval`/`clearInterval`
 * themselves rather than only on the signal's value, so a removed cleanup call would be caught
 * even though the value itself would look identical either way.
 */
describe('injectTick', () => {
    beforeEach(() => {
        vi.useFakeTimers();
    });

    afterEach(() => {
        vi.useRealTimers();
    });

    it('advances once per second and not more often', () => {
        vi.setSystemTime(0);
        const tick = TestBed.runInInjectionContext(() => injectTick());
        expect(tick()).toBe(0);

        vi.advanceTimersByTime(TICK_INTERVAL_MS - 1);
        expect(tick()).toBe(0);

        vi.advanceTimersByTime(1);
        expect(tick()).toBe(TICK_INTERVAL_MS);

        vi.advanceTimersByTime(TICK_INTERVAL_MS * 3);
        expect(tick()).toBe(TICK_INTERVAL_MS * 4);
    });

    it('shares one interval and one signal across every consumer', () => {
        const setIntervalSpy = vi.spyOn(globalThis, 'setInterval');

        const first = TestBed.runInInjectionContext(() => injectTick());
        const second = TestBed.runInInjectionContext(() => injectTick());

        expect(second).toBe(first);
        expect(setIntervalSpy).toHaveBeenCalledTimes(1);

        vi.advanceTimersByTime(TICK_INTERVAL_MS);
        expect(second()).toBe(first());
    });

    it('clears the interval when its injector is destroyed', () => {
        const setIntervalSpy = vi.spyOn(globalThis, 'setInterval');
        const clearIntervalSpy = vi.spyOn(globalThis, 'clearInterval');

        TestBed.runInInjectionContext(() => injectTick());
        expect(setIntervalSpy).toHaveBeenCalledTimes(1);
        const id = setIntervalSpy.mock.results[0].value;

        // The closest a unit test gets to "the application shuts down": `TestBed` destroys the
        // injector that created the token, which is exactly the injector whose `DestroyRef` the
        // factory registered its cleanup against. Without that registration nothing here would
        // ever call `clearInterval`, and this assertion would fail — the value alone would not
        // have caught it, since a leaked interval keeps advancing a signal nobody reads anymore.
        TestBed.resetTestingModule();

        expect(clearIntervalSpy).toHaveBeenCalledTimes(1);
        expect(clearIntervalSpy).toHaveBeenCalledWith(id);
    });

    it('starts a fresh clock, at the current time, for the next injector', () => {
        vi.setSystemTime(5_000);
        TestBed.runInInjectionContext(() => injectTick());
        TestBed.resetTestingModule();

        vi.setSystemTime(9_000);
        const tick = TestBed.runInInjectionContext(() => injectTick());
        expect(tick()).toBe(9_000);
    });
});
