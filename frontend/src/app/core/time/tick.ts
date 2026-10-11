import {computed, DestroyRef, effect, inject, InjectionToken, Signal, signal} from '@angular/core';

/**
 * How often the clock ticks (ISC-412: "re-rendered at most once a second"). Exported so a spec
 * can advance a fake clock by exactly this much rather than a guessed number.
 */
export const TICK_INTERVAL_MS = 1000;

/** The shared clock and the two calls that say somebody needs it, or no longer does. */
interface Clock {
    readonly now: Signal<number>;
    acquire(): void;
    release(): void;
}

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
 * <p>Reference-counted: the interval runs only while at least one consumer says it needs the
 * clock. The header reads it on every screen for the application's whole lifetime, and an idle
 * browser woke once a second for a number nobody was shown. Restarting sets the reading at once,
 * so a pass that appears after an idle hour does not start an hour in the past.
 *
 * <p>The factory runs inside an injection context, exactly like `RELOAD` in `update.store.ts`,
 * which is what makes `inject(DestroyRef)` legal here: its `onDestroy` ties the interval to the
 * lifetime of the injector that created it, so the clock stops when the application — or, in a
 * spec, `TestBed.resetTestingModule()` — tears that injector down. A `setInterval` with no such
 * hook is the bug this file exists to make impossible.
 */
const TICK = new InjectionToken<Clock>('leadgen.tick', {
    providedIn: 'root',
    factory: () => {
        const now = signal(Date.now());
        let users = 0;
        let id: ReturnType<typeof setInterval> | null = null;
        const stop = (): void => {
            if (id !== null) {
                clearInterval(id);
                id = null;
            }
        };
        inject(DestroyRef).onDestroy(stop);
        return {
            now: now.asReadonly(),
            acquire: () => {
                users++;
                if (id === null) {
                    now.set(Date.now());
                    id = setInterval(() => now.set(Date.now()), TICK_INTERVAL_MS);
                }
            },
            release: () => {
                users = Math.max(0, users - 1);
                if (users === 0) {
                    stop();
                }
            },
        };
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
 *
 * @param needed when the caller needs the clock running, read reactively; omitted, it needs it
 *     for as long as the calling injector lives. While no caller needs it, the reading stands
 *     still and no interval runs.
 */
export function injectTick(needed?: () => boolean): Signal<number> {
    const clock = inject(TICK);
    if (needed === undefined) {
        clock.acquire();
        inject(DestroyRef).onDestroy(() => clock.release());
        return clock.now;
    }
    // An effect, because "needed" is a signal and only an effect hears it change; its cleanup
    // runs on the next change and when the caller is destroyed, so every acquire is released.
    // A computed in between, so the effect wakes only when the answer flips: `needed` may read a
    // signal that is a new object every heartbeat, and each wake released and re-acquired the clock.
    const wanted = computed(needed);
    effect((onCleanup) => {
        if (wanted()) {
            clock.acquire();
            onCleanup(() => clock.release());
        }
    });
    return clock.now;
}
