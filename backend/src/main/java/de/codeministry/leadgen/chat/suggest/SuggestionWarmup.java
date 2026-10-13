/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.chat.suggest;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
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
 * <p><b>Two kinds of work.</b> The unpinned suggestions are one slot: asking for them while a call
 * runs marks them due once more, and that one more is computed from the data as it is then, so any
 * number of changes during a slow call cost a single further call for the newest snapshot. A pinned
 * context cannot be prepared ahead and carries its own sentences; those wait in a de-duplicated set
 * of at most {@link #PINNED_LIMIT}, and one more is dropped rather than queued, since its next open
 * asks again.
 *
 * <p><b>One worker thread.</b> A local model answers one request at a time anyway, and a second
 * call in flight would only race the first for the chat's day.
 */
@Slf4j
@Component
public class SuggestionWarmup implements DisposableBean {

    /** Enough for the pins of a working session, small against the chat's 200 calls a day. */
    static final int PINNED_LIMIT = 16;

    /** One phrasing to make: the key it is kept under and what the model is asked to phrase. */
    public record Job(String key, String snapshot, Map<String, String> sentences, String language) {}

    private final Phrasing phrasing;
    private final ObjectProvider<SuggestionService> suggestions;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(
            Thread.ofVirtual().name("suggestion-warmup").factory());

    private final Object lock = new Object();
    private final Map<String, Job> pinned = new LinkedHashMap<>();
    private boolean unpinnedDue;
    private boolean running;

    SuggestionWarmup(Phrasing phrasing, ObjectProvider<SuggestionService> suggestions) {
        this.phrasing = phrasing;
        this.suggestions = suggestions;
    }

    /**
     * A run ended or an application changed status: the unpinned suggestions are prepared for the
     * next open (ISC-529). After the commit, so a rolled-back write prepares nothing, and outside a
     * transaction at once ({@code PipelineRunRecorder.complete} runs in none). Only marks the work
     * due: the writer's thread never waits on the model.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void dataChanged(SuggestionDataChanged event) {
        log.debug("Suggestion data changed by {}; the unpinned suggestions are prepared", event.writer());
        unpinned();
    }

    /** Marks the unpinned suggestions due; they are computed when the worker gets to them. */
    void unpinned() {
        synchronized (lock) {
            unpinnedDue = true;
            start();
        }
    }

    /** Queues one pinned context's phrasing, unless it waits already or the set is full. */
    void pinned(Job job) {
        synchronized (lock) {
            if (pinned.containsKey(job.key())) {
                return;
            }
            if (pinned.size() >= PINNED_LIMIT) {
                log.debug(
                        "{} pinned suggestion phrasings wait already; this one is asked again on its next open",
                        PINNED_LIMIT);
                return;
            }
            pinned.put(job.key(), job);
            start();
        }
    }

    /** Whether nothing is running or waiting: what a caller that waits for the warm-up reads. */
    public boolean idle() {
        synchronized (lock) {
            return !running;
        }
    }

    @Override
    public void destroy() {
        worker.shutdownNow();
    }

    /** Under {@link #lock}. */
    private void start() {
        if (!running) {
            running = true;
            worker.execute(this::drain);
        }
    }

    private void drain() {
        while (true) {
            boolean unpinnedNow;
            Job next = null;
            synchronized (lock) {
                unpinnedNow = unpinnedDue;
                if (unpinnedNow) {
                    unpinnedDue = false;
                } else if (!pinned.isEmpty()) {
                    Iterator<Job> first = pinned.values().iterator();
                    next = first.next();
                    first.remove();
                } else {
                    running = false;
                    return;
                }
            }
            try {
                Optional<Job> job = unpinnedNow ? suggestions.getObject().unpinnedJob() : Optional.of(next);
                job.ifPresent(this::phrase);
            } catch (RuntimeException e) {
                log.warn("A suggestion phrasing failed; the catalog's sentences stay: {}", e.toString());
            }
        }
    }

    private void phrase(Job job) {
        if (phrasing.failed(job.key()) || phrasing.stored(job.key()).isPresent()) {
            return;
        }
        phrasing.attempt(job.snapshot(), job.sentences(), job.language());
    }
}
