/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.chat.suggest;

import de.codeministry.leadgen.chat.InProcess;
import de.codeministry.leadgen.config.ConfigProperties;
import de.codeministry.leadgen.event.DataChanged;
import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Phrases suggestions off the request thread, one model call at a time (spec 027).
 *
 * <p><b>The empty chat never waits on this.</b> It answers what {@link Phrasing#stored} holds, or
 * the catalog's sentences, and hands the miss here; the next open finds the phrasing (ISC-527,
 * ISC-528).
 *
 * <p><b>Three kinds of work.</b> A miss hands over the job its request built: snapshot, sentences
 * and the language it was asked in, and the worker phrases exactly that without reading the data
 * again (ISC-538). An unpinned miss is kept as the newest for its language, so a German miss and an
 * English open after it are each phrased in their own language; a pinned one waits in a set
 * de-duplicated by key, of at most {@link #PINNED_LIMIT}, and one more is dropped rather than
 * queued, since its next open asks again. A data change instead marks one slot due, "compute the
 * unpinned suggestions again", in the last open's language; any number of changes during a slow
 * call cost a single further call for the newest snapshot.
 *
 * <p><b>A data change costs nothing until the chat is used, and once per quiet spell after
 * that.</b> Before the first open since start, a run's end or a status change is dropped: nobody
 * may ever look, and each warm-up is a call from the chat's day (ISC-536). After it, a change
 * starts the warm-up only once {@code leadgen.chat.suggestion-quiet-window} has passed without a
 * further one, so triaging a hundred applications one by one costs one call, not a hundred
 * (ISC-537).
 *
 * <p><b>One phrasing at a time, on Boot's executor.</b> A local model answers one request at a time
 * anyway, and a second call in flight would only race the first for the chat's day. The
 * {@code running} flag holds that, not a pool of one: the work runs on Boot's
 * {@code applicationTaskExecutor}, as {@code LeadGenerationApplication} asks of every background
 * task; nothing here owns a thread (ISC-540).
 *
 * <p><b>The quiet window waits on the JDK's delay timer, not on Boot's {@code taskScheduler}.</b>
 * That scheduler has one thread, and a scheduled ingest pass holds it for as long as the pass runs:
 * a window waiting behind it passed only when the pass ended, and every unpinned miss in between
 * stayed unphrased. {@link CompletableFuture#delayedExecutor} times the window on a timer the JDK
 * shares and runs the short hand-over to {@link #start()} on its common pool; the phrasing itself
 * still runs on Boot's executor.
 *
 * <p><b>Quiet after shutdown.</b> A run can finish while the context closes, and its data change
 * reaches the listener synchronously; once {@link #destroy()} ran, such a trigger is dropped, and a
 * refusal from the executor never travels back to the writer (ISC-540).
 */
@Slf4j
@Component
public class SuggestionWarmup implements DisposableBean {

    /** Enough for the misses and pins of a working session, small against the chat's 200 calls a day. */
    static final int PINNED_LIMIT = 16;

    /** One phrasing to make: the key it is kept under and what the model is asked to phrase. */
    @InProcess
    public record Job(String key, String snapshot, Map<String, String> sentences, String language) {}

    private final Phrasing phrasing;
    private final ObjectProvider<SuggestionService> suggestions;
    private final TaskExecutor executor;
    private final Executor afterQuietWindow;

    private final Object lock = new Object();

    /** Pinned misses by key, in arrival order. */
    private final Map<String, Job> pinnedJobs = new LinkedHashMap<>();

    /** Unpinned misses by language, the newest per language: an older one is a snapshot already gone. */
    private final Map<String, Job> unpinnedMisses = new LinkedHashMap<>();

    /** Whether a data change asks for the unpinned suggestions to be computed again and phrased. */
    private boolean unpinnedDue;

    private boolean armed;
    private CompletableFuture<Void> pendingStart;

    /** Which scheduled start is the current one; an older one that already fired does nothing. */
    private long generation;

    private boolean running;
    private boolean closed;

    SuggestionWarmup(
            Phrasing phrasing,
            ObjectProvider<SuggestionService> suggestions,
            @Qualifier("applicationTaskExecutor") TaskExecutor executor,
            ConfigProperties properties) {
        this.phrasing = phrasing;
        this.suggestions = suggestions;
        this.executor = executor;
        Duration quietWindow = properties.chat().suggestionQuietWindow();
        this.afterQuietWindow = CompletableFuture.delayedExecutor(quietWindow.toNanos(), TimeUnit.NANOSECONDS);
    }

    /**
     * A run ended or an application changed status: the unpinned suggestions are prepared for the
     * next open (ISC-529) — if the chat was opened since start (ISC-536), and once the changes have
     * paused for the quiet window (ISC-537). After the commit, so a rolled-back write prepares
     * nothing, and outside a transaction at once ({@code PipelineRunRecorder.complete} runs in
     * none). Only schedules: the writer's thread never waits on the model.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void dataChanged(DataChanged event) {
        try {
            synchronized (lock) {
                if (!armed || closed) {
                    log.debug("Suggestion data changed by {}; the chat was not opened since start", event.writer());
                    return;
                }
                log.debug("Suggestion data changed by {}; the unpinned suggestions are prepared", event.writer());
                scheduleQuietWindow();
            }
        } catch (RuntimeException e) {
            // The writer's thread: whatever went wrong here is the warm-up's, never the write's.
            log.warn("The suggestion warm-up could not be scheduled after a data change: {}", e.toString());
        }
    }

    /** The chat was opened: from now on a data change prepares the next open (ISC-536). */
    void opened() {
        synchronized (lock) {
            armed = true;
        }
    }

    /**
     * An unpinned open missed: keep the job its request built — its snapshot, its sentences, its
     * language — as the newest for that language, so a German and an English miss are each phrased
     * in their own language (ISC-538). Phrased at once, unless changes are still arriving: then it
     * waits for the quiet window like they do, and a reader who reopens the chat after every move
     * costs one call per pause, not one per move (ISC-537).
     */
    void missed(Job job) {
        synchronized (lock) {
            unpinnedMisses.put(job.language(), job);
            if (pendingStart == null) {
                start();
            }
        }
    }

    /**
     * A pinned open missed: queue the job its request built, unless that key waits already or the set
     * is full, and make sure the worker runs (ISC-528).
     */
    void pinned(Job job) {
        synchronized (lock) {
            if (!pinnedJobs.containsKey(job.key())) {
                if (pinnedJobs.size() >= PINNED_LIMIT) {
                    log.debug(
                            "{} pinned suggestion phrasings wait already; this one is asked again on its next open",
                            PINNED_LIMIT);
                } else {
                    pinnedJobs.put(job.key(), job);
                }
            }
            // Also when the key waits already: a start the executor refused left it there.
            start();
        }
    }

    /**
     * Whether nothing is running, waiting or scheduled: what a caller that waits for the warm-up
     * reads. A start the quiet window still holds back counts as work.
     */
    public boolean idle() {
        synchronized (lock) {
            return !running && pendingStart == null;
        }
    }

    @Override
    public void destroy() {
        synchronized (lock) {
            closed = true;
            unpinnedDue = false;
            pinnedJobs.clear();
            unpinnedMisses.clear();
            if (pendingStart != null) {
                pendingStart.cancel(false);
                pendingStart = null;
            }
        }
    }

    /**
     * Under {@link #lock}: (re)starts the quiet window, superseding a start still pending. The old
     * start is let go before the new one is asked for, so a refusal never leaves a cancelled one
     * behind as pending.
     */
    private void scheduleQuietWindow() {
        long current = ++generation;
        if (pendingStart != null) {
            pendingStart.cancel(false);
            pendingStart = null;
        }
        pendingStart = CompletableFuture.runAsync(() -> quietWindowPassed(current), afterQuietWindow);
    }

    /**
     * The changes paused for the quiet window: the unpinned suggestions are computed again, and the
     * misses that waited for it are phrased. A start a newer change superseded does nothing, even when
     * it fired before its cancel reached it. Only a data change schedules a window, so a window that
     * passes always asks for the recompute.
     */
    private void quietWindowPassed(long scheduled) {
        synchronized (lock) {
            if (scheduled != generation) {
                return;
            }
            pendingStart = null;
            unpinnedDue = true;
            start();
        }
    }

    /** Under {@link #lock}. */
    private void start() {
        if (running || closed) {
            return;
        }
        running = true;
        try {
            executor.execute(this::drain);
        } catch (TaskRejectedException e) {
            running = false;
            log.warn(
                    "The suggestion warm-up was refused by the task executor; the next trigger asks again: {}",
                    e.toString());
        }
    }

    private void drain() {
        while (true) {
            Job next = null;
            boolean recompute = false;
            synchronized (lock) {
                if (closed) {
                    running = false;
                    return;
                }
                if (unpinnedDue) {
                    unpinnedDue = false;
                    recompute = true;
                } else if (!unpinnedMisses.isEmpty() && pendingStart == null) {
                    next = take(unpinnedMisses);
                } else if (!pinnedJobs.isEmpty()) {
                    next = take(pinnedJobs);
                } else {
                    running = false;
                    return;
                }
            }
            try {
                Optional<Job> job = recompute ? suggestions.getObject().unpinnedJob() : Optional.of(next);
                job.ifPresent(this::phrase);
            } catch (RuntimeException e) {
                log.warn("A suggestion phrasing failed; the catalog's sentences stay: {}", e.toString());
            }
        }
    }

    private static Job take(Map<String, Job> queue) {
        Iterator<Job> first = queue.values().iterator();
        Job job = first.next();
        first.remove();
        return job;
    }

    private void phrase(Job job) {
        if (phrasing.skipped(job.key()) || phrasing.stored(job.key()).isPresent()) {
            return;
        }
        phrasing.attempt(job.snapshot(), job.sentences(), job.language());
    }
}
