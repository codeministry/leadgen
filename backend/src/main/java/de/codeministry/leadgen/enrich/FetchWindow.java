/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.enrich;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.BooleanSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The fetch rate limit, as one window for the whole process.
 *
 * <p>It used to live inside {@link AdFetcher}, one per pass, which was correct while a pass
 * was the only thing that fetched. It stopped being correct the moment a person could ask
 * for one ad by hand: a second window beside the run's would let the two together ask a
 * portal twice as often as {@code rate_limit_per_minute} says, and the limit is a promise
 * to the portal, not to the pass. So the window is a singleton and every fetcher draws from
 * it; what stays per pass is the budget, which is a promise about the pass.
 *
 * <p>Two ways to take a permit, because the two callers want different things. The run
 * {@linkplain #awaitTake waits}, because nobody is watching it and waiting is how a backlog
 * clears. A button {@linkplain #tryTake asks once}, because a person is watching it, and a
 * spinner that turns for a minute is worse than a sentence saying to try again.
 */
@Component
public class FetchWindow {

    /**
     * The window this process is polite within, and the longest it ever waits at once.
     */
    static final Duration WINDOW = Duration.ofMinutes(1);

    private final Deque<Instant> recentRequests = new ArrayDeque<>();

    /**
     * The window's only source of time.
     *
     * <p>Injectable for one reason: a sliding window differs from a fixed one exactly at a
     * minute boundary, and a test that has to wait sixty seconds to say so is a test nobody
     * runs. It is a field rather than a parameter because the window is stateful and every
     * reading has to come from the same clock as the entries already in it.
     */
    private final Clock clock;

    /**
     * The one Spring uses. Marked, because a bean with two constructors and no mark is a bean
     * Spring instantiates with neither, and the error names a default constructor instead.
     */
    @Autowired
    public FetchWindow() {
        this(Clock.systemUTC());
    }

    FetchWindow(Clock clock) {
        this.clock = clock;
    }

    /**
     * A permit if the window has one now, and never a wait.
     *
     * <p>A sliding window rather than a fixed one: twenty a minute has to mean twenty in any
     * sixty seconds, not twenty at the top of each minute and forty across the boundary.
     *
     * @param perMinute {@code rate_limit_per_minute} as the caller's configuration states it.
     *                  A parameter rather than a field, so a reloaded configuration applies
     *                  from the next permit on without the window having to be rebuilt.
     */
    public synchronized boolean tryTake(int perMinute) {
        Instant now = clock.instant();
        Instant cutoff = now.minus(WINDOW);
        while (!recentRequests.isEmpty() && recentRequests.peekFirst().isBefore(cutoff)) {
            recentRequests.removeFirst();
        }
        if (recentRequests.size() >= perMinute) {
            return false;
        }
        recentRequests.addLast(now);
        return true;
    }

    /**
     * A permit, waiting for the window to free one for as long as the caller still wants it.
     *
     * <p>What is <em>not</em> relaxed is the window. This waits for a permit the window would
     * have granted anyway; it never takes one it would have refused.
     *
     * @param giveUp asked each time the window is full, before any wait. The run answers it
     *               with its budget, which stays the run's own: the window is shared, and how
     *               long one pass is prepared to wait is not something another caller can
     *               spend.
     * @return false when the caller gave up or the wait was interrupted.
     */
    public boolean awaitTake(int perMinute, BooleanSupplier giveUp) {
        while (!tryTake(perMinute)) {
            if (giveUp.getAsBoolean()) {
                return false;
            }
            if (!sleepUntilAPermitIsFree()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Until the oldest request in the window ages out, and no longer.
     *
     * <p>With the window shared the permit may be gone again by the time this wakes, taken
     * by the other caller; {@link #awaitTake} then simply waits for the next one.
     *
     * @return false when the wait was interrupted, which ends the pass rather than
     * swallowing the flag: an interrupt during a shutdown must not turn into a
     * twelve-minute wait nobody asked for.
     */
    private boolean sleepUntilAPermitIsFree() {
        Duration wait;
        synchronized (this) {
            Instant oldest = recentRequests.peekFirst();
            if (oldest == null) {
                return true;
            }
            wait = Duration.between(clock.instant(), oldest.plus(WINDOW));
        }
        if (wait.isNegative() || wait.isZero()) {
            return true;
        }
        try {
            pause(wait.plusMillis(50));
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Forgets every request in the window. For tests only, and package-private for that reason.
     *
     * <p>A singleton keeps its minute across the tests that share a Spring context, which is
     * the point in production and a leak in a suite: measured, one test that spent the window
     * made the next one that fetched wait the real sixty seconds out, and the enrichment suite
     * went from seconds to over a minute. Nothing in the application has a reason to forget a
     * request it made.
     */
    synchronized void clear() {
        recentRequests.clear();
    }

    /**
     * The one place real time is spent, and the seam a test replaces.
     *
     * <p>The wait is computed from the injectable clock and served by the real one, so a
     * frozen clock would otherwise mean a minute of actual sleeping and then a window that
     * never frees. Overridden, a test can move the clock the same amount instead — the same
     * reason {@link #clock} is injectable at all.
     */
    void pause(Duration wait) throws InterruptedException {
        Thread.sleep(wait.toMillis());
    }
}
