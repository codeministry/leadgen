/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.concurrent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The helper on its own, without Spring. Timings are generous on purpose: a busy CI runner
 * must not turn a width-4 run into a red build, and the tighter numbers belong to the stages'
 * own probes, introduced when they adopt this.
 */
@Timeout(10)
class BoundedWorkTest {

    private static final List<Integer> EIGHT = IntStream.range(0, 8).boxed().toList();

    @Test
    void widthOneIsThePlainLoopOnTheCallersThread() {
        Thread caller = Thread.currentThread();
        List<Integer> seen = new CopyOnWriteArrayList<>();
        Set<Thread> threads = ConcurrentHashMap.newKeySet();

        List<String> results = BoundedWork.forEach(1, EIGHT, i -> {
            seen.add(i);
            threads.add(Thread.currentThread());
            return "r" + i;
        });

        assertThat(seen).containsExactlyElementsOf(EIGHT);
        assertThat(threads).containsExactly(caller);
        assertThat(results).containsExactly("r0", "r1", "r2", "r3", "r4", "r5", "r6", "r7");
    }

    @Test
    void aWidthBelowOneMeansOne() {
        Thread caller = Thread.currentThread();
        Set<Thread> threads = ConcurrentHashMap.newKeySet();

        BoundedWork.forEach(0, EIGHT, i -> threads.add(Thread.currentThread()));

        assertThat(threads).containsExactly(caller);
    }

    @Test
    void widthFourOverEightSlowItemsIsFasterAndKeepsListOrder() {
        long started = System.nanoTime();

        List<String> results = BoundedWork.forEach(4, EIGHT, i -> {
            // Earlier items sleep longer, so list order cannot come from completion order.
            sleep(Duration.ofMillis(100 + (8 - i) * 5L));
            return "r" + i;
        });

        Duration took = Duration.ofNanos(System.nanoTime() - started);
        assertThat(took).isLessThan(Duration.ofMillis(650));
        assertThat(results).containsExactly("r0", "r1", "r2", "r3", "r4", "r5", "r6", "r7");
    }

    @Test
    void neverMoreThanWidthBodiesAtOnce() {
        AtomicInteger running = new AtomicInteger();
        AtomicInteger highWater = new AtomicInteger();
        List<Integer> items = IntStream.range(0, 24).boxed().toList();

        BoundedWork.forEach(3, items, i -> {
            highWater.accumulateAndGet(running.incrementAndGet(), Math::max);
            sleep(Duration.ofMillis(30));
            running.decrementAndGet();
            return i;
        });

        // The invariant, and the proof that it actually ran side by side.
        assertThat(highWater.get()).isLessThanOrEqualTo(3);
        assertThat(highWater.get()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void theStopSkipsWhatHasNotStartedAndKeepsWhatFinished() {
        List<Integer> started = new CopyOnWriteArrayList<>();

        // Item 2 answers "budget exhausted" quickly; with width 2 it starts only after item 0
        // or 1 has finished, and nothing from item 4 on can have started before it answered.
        List<Optional<String>> results = BoundedWork.forEach(
                2,
                EIGHT,
                i -> {
                    started.add(i);
                    sleep(Duration.ofMillis(i == 2 ? 20 : 150));
                    return i == 2 ? "stop" : "r" + i;
                },
                "stop"::equals);

        assertThat(results).hasSize(8);
        assertThat(results.get(0)).contains("r0");
        assertThat(results.get(1)).contains("r1");
        assertThat(results.get(2)).contains("stop");
        assertThat(results.subList(4, 8)).allMatch(Optional::isEmpty);
        assertThat(started).doesNotContain(4, 5, 6, 7);
        // Whatever started was kept, and whatever did not is marked as skipped.
        for (int i = 0; i < 8; i++) {
            assertThat(results.get(i).isPresent()).isEqualTo(started.contains(i));
        }
    }

    @Test
    void theStopAtWidthOneIsTodaysBreak() {
        List<Optional<String>> results = BoundedWork.forEach(1, EIGHT, i -> i == 3 ? "stop" : "r" + i, "stop"::equals);

        assertThat(results.subList(0, 3)).containsExactly(Optional.of("r0"), Optional.of("r1"), Optional.of("r2"));
        assertThat(results.get(3)).contains("stop");
        assertThat(results.subList(4, 8)).allMatch(Optional::isEmpty);
    }

    @Test
    void aThrowStopsHandingOutAndIsRethrownOnceTheInFlightItemsFinished() {
        List<Integer> started = new CopyOnWriteArrayList<>();
        List<Integer> finished = new CopyOnWriteArrayList<>();
        IllegalStateException boom = new IllegalStateException("item 0 failed");

        // Width 2: items 0 and 1 are in flight together. Item 0 throws early; item 1 is still
        // running then and must finish; items 2..7 are handed out only after a permit frees,
        // which is after the throw, so none of them may start.
        assertThatThrownBy(() -> BoundedWork.forEach(2, EIGHT, i -> {
                    started.add(i);
                    if (i == 0) {
                        sleep(Duration.ofMillis(30));
                        throw boom;
                    }
                    sleep(Duration.ofMillis(150));
                    finished.add(i);
                    return i;
                }))
                .isSameAs(boom);

        assertThat(started).containsExactlyInAnyOrder(0, 1);
        assertThat(finished).containsExactly(1);
    }

    @Test
    void anErrorWinsOverARuntimeExceptionAndTheOtherIsSuppressed() {
        List<Integer> finished = new CopyOnWriteArrayList<>();
        IllegalStateException first = new IllegalStateException("item 0 failed first");
        AssertionError worse = new AssertionError("item 1 failed worse");

        assertThatThrownBy(() -> BoundedWork.forEach(3, EIGHT, i -> {
                    if (i == 0) {
                        sleep(Duration.ofMillis(30));
                        throw first;
                    }
                    if (i == 1) {
                        sleep(Duration.ofMillis(60));
                        throw worse;
                    }
                    sleep(Duration.ofMillis(120));
                    finished.add(i);
                    return i;
                }))
                .isSameAs(worse)
                .satisfies(e -> assertThat(e.getSuppressed()).containsExactly(first));

        // Item 2 was in flight with the two failures and kept its side effect; nothing later started.
        assertThat(finished).containsExactly(2);
    }

    @Test
    void anEmptyListDoesNothing() {
        assertThat(BoundedWork.forEach(4, List.<Integer>of(), i -> i)).isEmpty();
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
