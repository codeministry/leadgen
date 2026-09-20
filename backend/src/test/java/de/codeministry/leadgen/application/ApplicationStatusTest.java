/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.application;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two rules the rest of the application reads off this enum.
 *
 * <p>A plain unit test and not a Spring one: both answers are decisions the enum makes on
 * its own, and three services plus the browser depend on them agreeing.
 */
class ApplicationStatusTest {

    @Test
    void letsAnUndecidedApplicationBePreparedOrDecidedAgainstAndNothingElse() {
        List<ApplicationStatus> expected = List.of(
            ApplicationStatus.NEW,
            ApplicationStatus.SHORTLISTED,
            ApplicationStatus.PACKAGED,
            ApplicationStatus.REJECTED,
            ApplicationStatus.EXPIRED);

        assertThat(ApplicationStatus.NEW.allowedNext()).containsExactlyElementsOf(expected);
        assertThat(ApplicationStatus.SHORTLISTED.allowedNext()).containsExactlyElementsOf(expected);
    }

    @Test
    void refusesEveryRouteAroundThePackage() {
        // The states that mean work left the machine, plus the two ways of closing one that
        // did. Reaching any of them from NEW would claim a document that does not exist.
        assertThat(ApplicationStatus.NEW.allowedNext())
            .doesNotContain(
                ApplicationStatus.SENT,
                ApplicationStatus.REPLIED,
                ApplicationStatus.INTERVIEW,
                ApplicationStatus.OFFER,
                ApplicationStatus.WON,
                ApplicationStatus.LOST);
    }

    @Test
    void leavesEveryOtherStateFree() {
        // Nine of eleven answer "all eleven", and that is the point: the operator is the
        // authority on their own mailbox everywhere the package is not at stake.
        List<ApplicationStatus> all = Arrays.asList(ApplicationStatus.values());
        for (ApplicationStatus status : all) {
            if (status.isBeforePackage()) {
                continue;
            }
            assertThat(status.allowedNext()).containsExactlyElementsOf(all);
        }
    }

    @Test
    void exemptsWhatSomebodyIsWorkingOnFromTheAgeRuleAndNothingElse() {
        // The exemption used to sit on PACKAGED, because the packager opened every
        // shortlisted offer there and exempting them all would have switched the archive
        // off. It opens them at NEW now, so the exemption moved with the meaning: a
        // prepared offer stays, an untouched one can age out.
        assertThat(ApplicationStatus.NEW.isLive()).isFalse();
        assertThat(ApplicationStatus.SHORTLISTED.isLive()).isTrue();
        assertThat(ApplicationStatus.PACKAGED.isLive()).isTrue();
        assertThat(ApplicationStatus.SENT.isLive()).isTrue();
        assertThat(ApplicationStatus.WON.isLive()).isFalse();
        assertThat(ApplicationStatus.REJECTED.isLive()).isFalse();
    }
}
