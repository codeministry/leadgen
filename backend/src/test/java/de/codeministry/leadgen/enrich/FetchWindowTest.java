/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.enrich;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.*;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The sliding window, on its own and against a clock that can be moved.
 *
 * <p>These tests lived in {@link AdFetcherTest} while the window lived in the fetcher, and
 * they moved with it unchanged in meaning. What stayed behind there is the gate order and
 * the per-pass budget; what is here needs neither a portal nor a cache.
 *
 * <p>The property pinned here is the difference between this window and every off-the-shelf
 * one: it <b>slides</b>. Twenty a minute means twenty in any sixty seconds, not twenty at the
 * top of each minute and forty across the boundary.
 */
class FetchWindowTest {

    /**
     * A clock the test moves by hand. A sliding window differs from a fixed one only at a
     * boundary, and waiting sixty seconds to prove it is a test nobody runs.
     */
    private static final class TestClock extends Clock {
        private Instant now = Instant.parse("2026-09-02T12:00:00Z");

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    private final TestClock clock = new TestClock();

    private final List<Duration> waited = new ArrayList<>();

    /**
     * The wait is served by moving the same clock the window is read from, so the sliding
     * behaviour is exercised in full without a minute of real sleeping.
     */
    private final FetchWindow window = new FetchWindow(clock) {
        @Override
        void pause(Duration wait) {
            waited.add(wait);
            clock.advance(wait);
        }
    };

    @Test
    void freesOneTokenSixtySecondsAfterTheRequestThatTookIt() {
        // The sliding half. A fixed window would free all three at the top of the next
        // minute and allow six inside one real minute; this frees exactly the one that has
        // aged out, and does it at that request's own sixtieth second.
        window.tryTake(3);
        clock.advance(Duration.ofSeconds(20));
        window.tryTake(3);
        window.tryTake(3);

        assertThat(window.tryTake(3)).isFalse();

        // Past the first request's sixtieth second, and only that one token is back.
        clock.advance(Duration.ofSeconds(41));
        assertThat(window.tryTake(3)).isTrue();
        assertThat(window.tryTake(3)).isFalse();
    }

    @Test
    void waitsForTheWindowWhileTheCallerStillWantsThePermit() {
        // The window refuses rather than waits, which is right for the window and wrong
        // for the pass on top of it: a run stopped at one minute's worth and deferred the
        // rest, so a backlog needed one run per `rate_limit_per_minute` offers to clear.
        // Measured on the live database: 97 of 101 scored offers had never had their
        // original ad fetched at all.
        for (int i = 0; i < 3; i++) {
            assertThat(window.tryTake(3)).isTrue();
        }

        assertThat(window.awaitTake(3, () -> false)).isTrue();

        // It waited for a permit the window would have granted anyway; it did not take one
        // the window had refused. The wait is the first request's remaining sixty seconds.
        assertThat(waited).hasSize(1);
        assertThat(waited.getFirst()).isBetween(Duration.ofSeconds(59), Duration.ofSeconds(61));
    }

    @Test
    void givesUpWithoutWaitingOnceTheCallerSaysSo() {
        // The run answers `giveUp` with its budget. Spent, the answer has to be immediate:
        // the alternative is a stage that blocks until the backlog is gone.
        for (int i = 0; i < 3; i++) {
            window.tryTake(3);
        }

        assertThat(window.awaitTake(3, () -> true)).isFalse();
        assertThat(waited).isEmpty();
    }

    @Test
    void neverWaitsWhenAskedOnce() {
        // The button's half. A person is watching it, so a full window is an answer, not a
        // reason to hold the request open for a minute.
        for (int i = 0; i < 3; i++) {
            window.tryTake(3);
        }

        assertThat(window.tryTake(3)).isFalse();
        assertThat(waited).isEmpty();
    }
}
