/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.ingest;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * A pass nobody had to press a button for.
 *
 * <p>The tool's point is that the morning's offers are already sorted when the operator
 * opens the page, and until this existed that was true only if they had started the run
 * themselves the evening before. A deployment can schedule it from outside — the cluster
 * does, with a CronJob — but Docker Compose is the supported way to run this repository
 * and Compose schedules nothing.
 *
 * <p><b>Off unless a schedule is configured, and the switch is the schedule itself.</b>
 * {@code leadgen.ingest-cron} defaults to {@code -}, which is Spring's own disabled marker,
 * so the shipped artifact reads nobody's mailbox on its own. One key rather than an
 * interval plus a boolean, because two keys can disagree and the pair where the boolean is
 * off and the interval looks configured is exactly the one an operator misreads.
 *
 * <p><b>Why a cron expression and not a fixed delay.</b> A fixed delay has no way to say
 * "off": {@code fixedDelayString} takes no disabled marker and a zero delay is a busy loop.
 * The alternative was {@code @ConditionalOnProperty} on this bean, and that is the trap
 * here: {@code processAot} evaluates conditions at build time, so the image would be built
 * with the schedule off and setting the variable at runtime would change nothing, silently.
 * A placeholder inside {@code @Scheduled} is resolved when the task is registered, which is
 * at runtime, in the image, with the operator's environment.
 *
 * <p>The timezone is the JVM's. Naming one here would be wiring in where the operator
 * lives, and `TZ` on the container says it better anyway.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class ScheduledPass {

    /** Spring's marker for "registered nothing". Also this key's default. */
    private static final String DISABLED = "-";

    private final IngestService ingest;

    @Value("${leadgen.ingest-cron:-}")
    private String cron;

    /**
     * Said once, at startup, because a schedule that is off looks exactly like a schedule
     * that is on and has not come round yet. That ambiguity lasts until the next morning,
     * and the next morning is the thing this exists for.
     */
    @EventListener(ApplicationReadyEvent.class)
    void announce() {
        if (DISABLED.equals(cron)) {
            log.info("No scheduled ingest: leadgen.ingest-cron is '{}'. A run starts when somebody asks for one", cron);
        } else {
            log.info("Scheduled ingest: leadgen.ingest-cron is '{}', in the JVM's timezone", cron);
        }
    }

    /**
     * <b>Nothing here may throw.</b> A scheduled method that does gets its stack trace
     * logged by the framework and is then run again next time regardless, which turns one
     * unreachable mailbox into a wall of identical traces on every tick.
     *
     * <p>A pass already running is not an error and not a queue. {@link IngestService} says
     * why: a second pass is the same work done twice, contending for the same rows.
     */
    @Scheduled(cron = "${leadgen.ingest-cron:-}")
    void run() {
        try {
            IngestReport report = ingest.run();
            log.info(
                    "Scheduled pass finished: {} extracted, {} written",
                    report.extracted(),
                    report.written());
        } catch (IngestService.AlreadyRunning e) {
            log.info("Scheduled pass skipped: {}", e.getMessage());
        } catch (RuntimeException e) {
            log.error("Scheduled pass failed", e);
        }
    }
}
