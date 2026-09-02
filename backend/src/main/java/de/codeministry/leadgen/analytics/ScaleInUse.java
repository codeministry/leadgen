package de.codeministry.leadgen.analytics;

import java.time.Instant;

/**
 * One combination of ruleset and judge that the archive's scores were produced under.
 *
 * <p>This is what makes the "today's rules" caveat falsifiable instead of merely stated. One
 * row means every score on the screen came off one scale and the caveat is currently
 * harmless. Two rows mean the archive already mixes two scales, every comparison across
 * time is suspect, and the screen can say so — which is the same invariant the scoring
 * section states in prose: two judges are two scales, and the shortlist threshold is one
 * number read against both.
 */
public record ScaleInUse(
        String rulesetVersion, String scoreModel, int offers, Instant firstScoredAt, Instant lastScoredAt) {}
