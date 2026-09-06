/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.score;

import java.util.List;

/**
 * What an offer is worth, and why.
 *
 * @param value          0 to 100, or <b>null</b> when no language model was available. Null is not
 *                       zero: the deterministic factors still produced reasons, but a total computed from
 *                       part of the weights would not be comparable to one computed from all of them, and
 *                       two runs of the same offer would differ by whether a key happened to be configured.
 * @param reasons        every factor that contributed, deterministic and judged alike. A number
 *                       without a reason gets ignored within a week.
 * @param model          which model judged, or null when none did.
 * @param rulesetVersion the `version` of the rules that produced this, so a score can be
 *                       told apart from one produced under different weights.
 */
public record Score(Integer value, boolean hardPass, List<ScoreReason> reasons, String model, String rulesetVersion) {

    public static Score unscored(List<ScoreReason> deterministic, String rulesetVersion) {
        return new Score(null, true, deterministic, null, rulesetVersion);
    }

    /**
     * A judged score: the share of what was attainable, less the penalties.
     *
     * <p><b>The total renormalises over the factors that had something to say.</b> A flat
     * sum charges every offer for the fields its source never states — the newsletter
     * carries a rate in 0.0 % of offers and a workload in none of them — so a fifth of the
     * scale was unreachable for reasons no offer could influence. Measured before this
     * changed: 101 of 101 offers carried a 0-point `rate_fit` row, `industry_fit` never
     * fired once, and the highest score in the whole table was 53.
     *
     * <p>So {@link ScoreReason#maxPoints()} decides what counts. A factor that applies
     * contributes to both the earned and the attainable sum, including when it scored zero;
     * a factor that does not apply writes no row and is in neither. The consequence is
     * deliberate and worth naming: the less an ad states, the more its skill overlap
     * carries — an offer is judged on what it says.
     *
     * <p>Penalties stay absolute. They are stated on the 0-100 scale in
     * `scoring.penalties`, so a -30 is thirty points off the finished share rather than a
     * share of something. Both ends are clamped, because the thresholds are read on that
     * same scale and a number outside it cannot be compared against them.
     *
     * <p>A factory rather than an expression at each call site because there are two of
     * those, the synchronous run and the batch collector, and a shortlist whose halves
     * normalise differently is not a ranking.
     */
    public static Score of(List<ScoreReason> reasons, String model, String rulesetVersion) {
        int attainable = reasons.stream()
                .filter(Score::counts)
                .mapToInt(ScoreReason::maxPoints)
                .sum();
        int earned = reasons.stream()
                .filter(Score::counts)
                .mapToInt(ScoreReason::points)
                .sum();
        int penalties = reasons.stream()
                .filter(reason -> !counts(reason))
                .mapToInt(ScoreReason::points)
                .sum();

        int share = attainable == 0 ? 0 : (int) Math.round(100.0 * earned / attainable);
        int total = Math.max(0, Math.min(100, share + penalties));
        return new Score(total, true, List.copyOf(reasons), model, rulesetVersion);
    }

    /**
     * What was attainable, and therefore what the share is measured against.
     */
    private static boolean counts(ScoreReason reason) {
        return reason.maxPoints() > 0;
    }

    /**
     * The band names in `scoring.thresholds`, which the shortlist and the digest read.
     */
    public String band(int autoShortlist, int review) {
        if (value == null) {
            return "UNSCORED";
        }
        if (value >= autoShortlist) {
            return "SHORTLISTED";
        }
        return value >= review ? "REVIEW" : "DISCARDED";
    }
}
