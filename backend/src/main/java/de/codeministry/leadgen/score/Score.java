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
 * @param value 0 to 100, or <b>null</b> when no language model was available. Null is not
 *     zero: the deterministic factors still produced reasons, but a total computed from
 *     part of the weights would not be comparable to one computed from all of them, and
 *     two runs of the same offer would differ by whether a key happened to be configured.
 * @param reasons every factor that contributed, deterministic and judged alike. A number
 *     without a reason gets ignored within a week.
 * @param model which model judged, or null when none did.
 * @param rulesetVersion the `version` of the rules that produced this, so a score can be
 *     told apart from one produced under different weights.
 */
public record Score(Integer value, boolean hardPass, List<ScoreReason> reasons, String model, String rulesetVersion) {

    public static Score unscored(List<ScoreReason> deterministic, String rulesetVersion) {
        return new Score(null, true, deterministic, null, rulesetVersion);
    }

    /**
     * A judged score, with the total added up and clamped.
     *
     * <p>The weights sum to 100 and the penalties are negative, so a heavily penalised
     * offer can go below zero and a generous weight table above 100. Both are clamped: the
     * thresholds are stated on a 0-100 scale and a score outside it cannot be read against
     * them. It is a factory rather than an expression at each call site because there are
     * two of those now, the synchronous run and the batch collector, and a shortlist whose
     * halves clamp differently is not a ranking.
     */
    public static Score of(List<ScoreReason> reasons, String model, String rulesetVersion) {
        int total = reasons.stream().mapToInt(ScoreReason::points).sum();
        return new Score(Math.max(0, Math.min(100, total)), true, List.copyOf(reasons), model, rulesetVersion);
    }

    /** The band names in `scoring.thresholds`, which the shortlist and the digest read. */
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
