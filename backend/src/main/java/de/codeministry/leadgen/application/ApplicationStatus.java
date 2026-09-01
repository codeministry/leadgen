package de.codeministry.leadgen.application;

import java.util.List;

/**
 * Where an application stands. Eleven states, and the operator decides which one.
 *
 * <p><b>The transitions are documented, not enforced.</b> Every value here is entered by
 * hand about events the system never saw — it does not send, so it cannot know that a mail
 * went out or that someone replied. The operator is the authority on their own mailbox,
 * and a tool that refuses a correction because the path looks wrong is a tool they stop
 * updating after the second argument. What the endpoint does check is consistency: a SENT
 * application needs a date, because "sent, at some point" is not a fact anybody can act on.
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

    /** The five lanes the board groups these into; eleven columns cannot be read at a glance. */
    public static final List<Lane> LANES = List.of(
            new Lane("backlog", "Backlog", List.of(NEW, SHORTLISTED)),
            new Lane("prepared", "Prepared", List.of(PACKAGED)),
            new Lane("out", "Out", List.of(SENT, REPLIED)),
            new Lane("talking", "Talking", List.of(INTERVIEW, OFFER)),
            new Lane("closed", "Closed", List.of(WON, LOST, REJECTED, EXPIRED)));

    /** A state nothing follows. Reaching one is what stops the follow-up counter. */
    public boolean isClosed() {
        return this == WON || this == LOST || this == REJECTED || this == EXPIRED;
    }

    /** The mail has left, so a follow-up date starts meaning something. */
    public boolean isOut() {
        return this == SENT || this == REPLIED || this == INTERVIEW || this == OFFER;
    }

    public record Lane(String id, String label, List<ApplicationStatus> states) {}
}
