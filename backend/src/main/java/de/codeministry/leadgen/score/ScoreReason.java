/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.score;

/**
 * One factor and what it contributed.
 *
 * @param factor    a key from `scoring.weights` or `scoring.penalties`. The keys are the
 *                  contract with the configuration: a factor spelled differently is scored and then
 *                  unexplainable, because nothing links it back to the weight that produced it.
 * @param label     what to show a human. Written by whatever produced the points, so it can
 *                  name the actual skills that overlapped rather than restating the factor.
 * @param points    signed, and already weighted.
 * @param maxPoints what this factor could have contributed at best, which is what makes
 *                  the total a share rather than a sum.
 *                  <p><b>A factor with nothing to say emits no reason at all, and that is the whole
 *                  mechanism.</b> The sources state a rate in 0.0 % of offers and never a workload, so
 *                  scoring those as zero deducted a fifth of the scale from every offer alike, for
 *                  something no offer could have done anything about — measured: 101 of 101 offers held
 *                  a 0-point `rate_fit` row and the highest score in the table was 53. An absent row is
 *                  out of both sums, so an offer is measured against what it actually states; a factor
 *                  that had something to say and scored badly writes a 0-point row and stays in the
 *                  denominator.
 *                  <p>Zero for a penalty. Penalties are absolute deductions on the 0-100 scale, applied
 *                  after the share is computed, so they never belong in what was attainable.
 */
public record ScoreReason(String factor, String label, int points, int maxPoints, String topic) {

    public static final String INTEREST = "interest_fit";
    public static final String DISINTEREST = "disinterest_fit";

    /**
     * The same two effects when the judge found the topic rather than an alias did. A factor
     * of their own so a re-total can tell a stored answer from a rule it recomputes, and
     * worth exactly what the alias row is worth: {@link Score} keeps one per family.
     */
    public static final String INTEREST_JUDGED = "interest_judged";

    public static final String DISINTEREST_JUDGED = "disinterest_judged";

    /**
     * Every reason that is not about a profile topic, which is every reason but two.
     */
    public ScoreReason(String factor, String label, int points, int maxPoints) {
        this(factor, label, points, maxPoints, null);
    }

    /**
     * A bonus for the heaviest interest topic the advert names. Absolute, like
     * {@link #bonus}, because inside the attainable pool a matched topic would lower any
     * offer whose existing share is higher than the topic's weight: a 60 dropped to 54 on
     * a weight-3 match when it was measured.
     */
    public static ScoreReason interest(String topic, int points) {
        return new ScoreReason(INTEREST, "interest: " + topic, points, 0, topic);
    }

    public static ScoreReason judgedInterest(String topic, int points) {
        return new ScoreReason(INTEREST_JUDGED, "interest (judged): " + topic, points, 0, topic);
    }

    public static ScoreReason judgedDisinterest(String topic, int points) {
        return new ScoreReason(DISINTEREST_JUDGED, "disinterest (judged): " + topic, points, 0, topic);
    }

    /**
     * Which effect this row is, whoever found it: the interest bonus, the disinterest penalty,
     * or its own factor for everything else.
     */
    public String family() {
        return switch (factor) {
            case INTEREST_JUDGED -> INTEREST;
            case DISINTEREST_JUDGED -> DISINTEREST;
            default -> factor;
        };
    }

    public boolean judgedTopic() {
        return INTEREST_JUDGED.equals(factor) || DISINTEREST_JUDGED.equals(factor);
    }

    /**
     * A penalty for the heaviest disinterest topic the advert names, charged once.
     */
    public static ScoreReason disinterest(String topic, int points) {
        return new ScoreReason(DISINTEREST, "disinterest: " + topic, points, 0, topic);
    }

    /**
     * A judged deduction: subtracted from the finished share, never part of it.
     */
    public static ScoreReason penalty(String factor, String label, int points) {
        return new ScoreReason(factor, label, points, 0);
    }

    /**
     * An absolute addition, on the same footing as a penalty and for the same reason.
     *
     * <p>It exists for the one factor that measures the <em>ad</em> rather than the fit:
     * how much of the engagement's shape is stated. In the attainable pool that factor
     * punishes partial disclosure — an ad naming only a start date would score 3 of 10 and
     * come out below one that says nothing at all, since saying nothing keeps the factor
     * out of the denominator entirely. As a bonus it is monotone: nothing stated adds
     * nothing, everything stated adds the full weight, and no ad is ever charged for a
     * field its portal does not carry.
     */
    public static ScoreReason bonus(String factor, String label, int points) {
        return new ScoreReason(factor, label, points, 0);
    }
}
