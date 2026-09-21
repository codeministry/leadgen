/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.ingest;

import de.codeministry.leadgen.Databases;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * The scheduled pass, in both of its two states.
 *
 * <p>The shipped default is the interesting one. A schedule that is off has to be off in
 * the artifact as well as in the source, and the failure mode being guarded against is not
 * a wrong interval but a container that quietly starts reading somebody's mailbox.
 */
@Testcontainers
class ScheduledPassTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = Databases.postgres();

    @Nested
    @SpringBootTest
    class WithNoScheduleConfigured {

        @Autowired
        private ScheduledTaskHolder tasks;

        @Test
        void registersNoTaskAtAll() {
            // `-` is Spring's disabled marker and it is this key's default, so a fresh
            // clone and the shipped image both register nothing. Asserted against the
            // registry rather than by waiting: an absence cannot be proven by a timeout.
            assertThat(tasks.getScheduledTasks())
                    .as("the shipped artifact schedules no ingest of its own")
                    .noneSatisfy(task -> assertThat(task.getTask())
                            .isInstanceOf(CronTask.class)
                            .extracting(cron -> ((CronTask) cron).getExpression())
                            .isNotEqualTo("-"));
        }
    }

    @Nested
    @SpringBootTest(properties = "leadgen.ingest-cron=* * * * * *")
    class WithASchedule {

        private final CountDownLatch ran = new CountDownLatch(1);

        @MockitoBean
        private IngestService ingest;

        @Test
        void runsWithoutAnybodyAskingForIt() throws InterruptedException {
            when(ingest.run()).thenAnswer(invocation -> {
                ran.countDown();
                return new IngestReport(
                        java.util.List.of(), 0, null, null, null, null, null, null, null, null, null, null, null);
            });

            assertThat(ran.await(10, TimeUnit.SECONDS))
                    .as("a pass started on the schedule, with no request and no button")
                    .isTrue();
        }
    }
}
