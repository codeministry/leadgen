/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.concurrent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Predicate;
import lombok.extern.slf4j.Slf4j;

/**
 * A stage's per-advert loop, run at most {@code width} items at a time.
 *
 * <p>Width 1 or less is the plain loop on the caller's thread — the code path of today is the
 * code path of a width nobody set, and a failure propagates at the item that threw, exactly as
 * a {@code for} loop's would. Above 1 each item runs on its own virtual thread behind a
 * {@link Semaphore}: the work is waiting on a socket, so the width is the only number that
 * matters, and the caller's thread hands the items out in list order and blocks while all
 * permits are taken.
 *
 * <p>A body that throws stops the loop above width 1 the way it does at width 1: no item is
 * handed out after the throw. Items already running finish and keep what they wrote, and once
 * nothing is still in flight one failure is rethrown — an {@link Error} before any
 * {@link RuntimeException}, otherwise the one with the lowest list index — with the others
 * attached as suppressed and each logged, because {@code StageLog.time} records only the first
 * message. The throws that reach this class are systemic ones (a model's failure is answered
 * empty by the stage's own client), so continuing would spend one budget unit and one request
 * per remaining advert on a run that is already lost.
 *
 * <p>Not a bean on purpose: it holds no state between calls, and the width is read from the
 * stage's own configuration snapshot at the start of each run.
 *
 * <p>The body runs on another thread above width 1, so it must not rely on anything bound to the
 * caller's thread — a Spring transaction, a request scope, an MDC.
 */
@Slf4j
public final class BoundedWork {

    private BoundedWork() {}

    /**
     * What a stage appends to its summary log line: nothing at width 1, so a sequential run logs
     * what it always has, and {@code " at width N"} above it.
     */
    public static String atWidth(int width) {
        return width > 1 ? " at width " + width : "";
    }

    /**
     * Runs {@code body} over every item and returns the results in list order.
     *
     * @param width at most this many bodies run at once; 1 or less means the plain loop on the
     *     caller's thread
     * @param body must not return {@code null}
     */
    public static <T, R> List<R> forEach(int width, List<T> items, Function<? super T, ? extends R> body) {
        List<Optional<R>> results = forEach(width, items, body, result -> false);
        List<R> unwrapped = new ArrayList<>(results.size());
        for (Optional<R> result : results) {
            unwrapped.add(result.orElseThrow());
        }
        return Collections.unmodifiableList(unwrapped);
    }

    /**
     * Runs {@code body} over the items until a result {@code stopsTheRest} — a refused budget,
     * typically — and returns one entry per item, in list order.
     *
     * <p>At width 1 this is a loop with a {@code break}: the stopping result is kept and every
     * later entry is empty. Above 1 no item starts after the flag is set; up to {@code width - 1}
     * items already running decide for themselves, so several stopping results may be present.
     * An item that never started is {@link Optional#empty()}, which is the one meaning an empty
     * entry has. This bounds how many items start, not how many requests are sent — that is
     * {@code LlmBudget}'s job.
     *
     * @param width at most this many bodies run at once; 1 or less means the plain loop on the
     *     caller's thread
     * @param body must not return {@code null}, or a finished item would read as a skipped one
     * @param stopsTheRest asked of each result once its body has returned, before the item's
     *     permit is released
     */
    public static <T, R> List<Optional<R>> forEach(
            int width, List<T> items, Function<? super T, ? extends R> body, Predicate<? super R> stopsTheRest) {
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(stopsTheRest, "stopsTheRest");
        return width <= 1 ? inOrder(items, body, stopsTheRest) : bounded(width, items, body, stopsTheRest);
    }

    private static <T, R> List<Optional<R>> inOrder(
            List<T> items, Function<? super T, ? extends R> body, Predicate<? super R> stopsTheRest) {
        List<Optional<R>> results = new ArrayList<>(Collections.nCopies(items.size(), Optional.empty()));
        for (int i = 0; i < items.size(); i++) {
            R result = Objects.requireNonNull(body.apply(items.get(i)), "body returned null");
            results.set(i, Optional.of(result));
            if (stopsTheRest.test(result)) {
                break;
            }
        }
        return Collections.unmodifiableList(results);
    }

    private static <T, R> List<Optional<R>> bounded(
            int width, List<T> items, Function<? super T, ? extends R> body, Predicate<? super R> stopsTheRest) {
        Semaphore permits = new Semaphore(width);
        AtomicBoolean stopped = new AtomicBoolean();
        List<Future<R>> handedOut = new ArrayList<>(items.size());
        boolean interrupted = false;

        // close() waits for every submitted task, so each future is done once this block ends
        // and everything the bodies wrote happened before the results are read.
        try (ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor()) {
            for (T item : items) {
                try {
                    permits.acquire();
                } catch (InterruptedException e) {
                    interrupted = true;
                    break;
                }
                // Checked after the permit, because a permit is only released once the item
                // holding it has raised the stop, by its result or by throwing.
                if (stopped.get()) {
                    permits.release();
                    break;
                }
                handedOut.add(threads.submit(() -> {
                    try {
                        R result = Objects.requireNonNull(body.apply(item), "body returned null");
                        if (stopsTheRest.test(result)) {
                            stopped.set(true);
                        }
                        return result;
                    } catch (RuntimeException | Error e) {
                        stopped.set(true);
                        throw e;
                    } finally {
                        permits.release();
                    }
                }));
            }
        }

        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        return collect(items.size(), handedOut, interrupted);
    }

    private static <R> List<Optional<R>> collect(int size, List<Future<R>> handedOut, boolean interrupted) {
        List<Optional<R>> results = new ArrayList<>(Collections.nCopies(size, Optional.empty()));
        List<Throwable> failures = new ArrayList<>();
        for (int i = 0; i < handedOut.size(); i++) {
            Future<R> future = handedOut.get(i);
            if (future.state() == Future.State.SUCCESS) {
                results.set(i, Optional.of(future.resultNow()));
            } else {
                failures.add(future.exceptionNow());
            }
        }
        if (!failures.isEmpty()) {
            Throwable thrown = failures.stream()
                    .filter(Error.class::isInstance)
                    .findFirst()
                    .orElse(failures.getFirst());
            for (Throwable other : failures) {
                if (other != thrown) {
                    log.warn("Another item failed in the same run and is attached as suppressed", other);
                    thrown.addSuppressed(other);
                }
            }
            switch (thrown) {
                case RuntimeException e -> throw e;
                case Error e -> throw e;
                default -> throw new IllegalStateException(thrown);
            }
        }
        if (interrupted) {
            throw new IllegalStateException("Interrupted while handing out work; the items already running finished");
        }
        return Collections.unmodifiableList(results);
    }
}
