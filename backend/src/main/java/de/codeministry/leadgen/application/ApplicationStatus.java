/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.application;

import java.util.Arrays;
import java.util.List;

/**
 * Where an application stands. Eleven states, and the operator decides which one.
 *
 * <p><b>The transitions are documented, not enforced — with exactly one exception.</b> Every
 * value here is entered by hand about events the system never saw: it does not send, so it
 * cannot know that a mail went out or that someone replied. The operator is the authority on
 * their own mailbox, and a tool that refuses a correction because the path looks wrong is a
 * tool they stop updating after the second argument. What the endpoint does check is
 * consistency: a SENT application needs a date, because "sent, at some point" is not a fact
 * anybody can act on.
 *
 * <p>The exception is {@link #PACKAGED}, and it is not about the path being tidy. The folder
 * an application is sent from is built when somebody moves the application into that state,
 * so a route around it would produce a SENT application with nothing on disk behind it — a
 * claim about a document that does not exist. {@link #allowedNext()} therefore refuses to
 * leave a pre-package state for anything past it. Everything after PACKAGED stays free.
 */
public enum ApplicationStatus {
    NEW,
    SHORTLISTED,
    PACKAGED,
    SENT,
    REPLIED,
    INTERVIEW,
    OFFER,
    WON,
    LOST,
    REJECTED,
    EXPIRED;

    /**
     * The five lanes the board groups these into; eleven columns cannot be read at a glance.
     */
    public static final List<Lane> LANES = List.of(
            new Lane("backlog", "Backlog", List.of(NEW, SHORTLISTED)),
            new Lane("prepared", "Prepared", List.of(PACKAGED)),
            new Lane("out", "Out", List.of(SENT, REPLIED)),
            new Lane("talking", "Talking", List.of(INTERVIEW, OFFER)),
            new Lane("closed", "Closed", List.of(WON, LOST, REJECTED, EXPIRED)));

    /**
     * Every state, in declaration order. The answer {@link #allowedNext()} gives for all but
     * the two that come before a package.
     */
    private static final List<ApplicationStatus> ALL = List.of(values());

    /**
     * A state nothing follows. Reaching one is what stops the follow-up counter.
     */
    public boolean isClosed() {
        return this == WON || this == LOST || this == REJECTED || this == EXPIRED;
    }

    /**
     * Nobody has committed to this one yet: it is on the shortlist, or marked as interesting,
     * and no folder has been built for it.
     */
    public boolean isBeforePackage() {
        return this == NEW || this == SHORTLISTED;
    }

    /**
     * The operator has taken this up: not closed, and not the state an offer is opened at.
     *
     * <p>This used to exempt PACKAGED instead of NEW, because the packager opened an
     * application the moment it built a folder and treating that as "in progress" would have
     * exempted every offer that ever reached the shortlist from the age rule. The packager no
     * longer opens anything: the shortlist does, at NEW, and PACKAGED is now a decision a
     * person made. So the exemption moved with the meaning. Leaving it on PACKAGED would
     * archive the offers somebody is preparing and exempt the ones nobody has looked at,
     * which is the rule exactly inverted.
     */
    public boolean isLive() {
        return !isClosed() && this != NEW;
    }

    /**
     * Which states this one may be moved to.
     *
     * <p>Every state but the two before a package answers "all eleven" — the operator is the
     * authority and a correction has to be possible in any direction. From NEW or SHORTLISTED
     * the answer is the other one of the two, PACKAGED, and the two ways of deciding against
     * an offer without preparing it. Read by the endpoint, which refuses anything else with a
     * 409, and served to the browser so the picker and the board offer the same set rather
     * than keeping a second copy of this rule.
     */
    public List<ApplicationStatus> allowedNext() {
        if (!isBeforePackage()) {
            return ALL;
        }
        return Arrays.stream(values())
                .filter(next -> next.isBeforePackage() || next == PACKAGED || next == REJECTED || next == EXPIRED)
                .toList();
    }

    /**
     * The mail has left, so a follow-up date starts meaning something.
     */
    public boolean isOut() {
        return this == SENT || this == REPLIED || this == INTERVIEW || this == OFFER;
    }

    public record Lane(String id, String label, List<ApplicationStatus> states) {}
}
