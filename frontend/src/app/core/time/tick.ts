import {DestroyRef, inject, InjectionToken, Signal, signal} from '@angular/core';

/**
 * How often the clock ticks (ISC-412: "re-rendered at most once a second"). Exported so a spec
 * can advance a fake clock by exactly this much rather than a guessed number.
 */
export const TICK_INTERVAL_MS = 1000;

/**
 * The shared clock, one `setInterval` for the whole application (F46 spec, "core/time/tick.ts").
 *
 * <p>An `InjectionToken` with `providedIn: 'root'` rather than a `*.store.ts` triplet: there is
 * no external event to react to and nothing to reduce, only a value the browser produces by
 * itself once a second, so the NgRx event dialect used by the stores beside this file (see
 * `core/pwa/update.store.ts`) would be ceremony with nothing to hold. `providedIn: 'root'` is
 * what makes it a singleton — the factory runs once per application (once per test, since each
 * spec gets its own root injector), and every `injectTick()` call after the first returns the
 * same `Signal`, the same interval.
 *
 * <p>The factory runs inside an injection context, exactly like `RELOAD` in `update.store.ts`,
 * which is what makes `inject(DestroyRef)` legal here: its `onDestroy` ties the interval to the
 * lifetime of the injector that created it, so the clock stops when the application — or, in a
 * spec, `TestBed.resetTestingModule()` — tears that injector down. A `setInterval` with no such
 * hook is the bug this file exists to make impossible.
 */
const TICK = new InjectionToken<Signal<number>>('leadgen.tick', {
    providedIn: 'root',
    factory: () => {
        const now = signal(Date.now());
        const id = setInterval(() => now.set(Date.now()), TICK_INTERVAL_MS);
        inject(DestroyRef).onDestroy(() => clearInterval(id));
        return now.asReadonly();
    },
});

/**
 * The shared one-second clock, as "now" in epoch milliseconds (ISC-412).
 *
 * <p>A clock, not an elapsed-time helper: it answers what time it is, at one-second
 * granularity, and nothing about how long anything has been running. A component that shows
 * elapsed time reads a start instant of its own and computes `injectTick()() - startedAt` —
 * that subtraction is the caller's, on purpose, so this file stays reusable for whatever else
 * ends up wanting a shared clock.
 */
export function injectTick(): Signal<number> {
    return inject(TICK);
}
