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
 * The half of the score that needs judgement: role fit, and the three penalties.
 *
 * <p>An interface with one production implementation, because the point is that there may
 * be <em>none</em>. With no key configured the pipeline runs without a judge and produces
 * an unscored shortlist rather than failing — the tool has to work weaker, not stop.
 */
public interface Judge {

    /** The factors a judge is asked about. The rest are decided by {@link RuleScorer}. */
    List<String> JUDGED = List.of("role_fit", "stack_mismatch_dominant", "role_mismatch", "vague_description");

    /** Which model answered, for the record on every score it produced. */
    String model();

    /**
     * @return one reason per factor the judge has an opinion about. An empty list is a
     *     legitimate answer and means the offer earns no role-fit points and no penalties.
     */
    List<ScoreReason> judge(ScoreCandidate offer);

    /**
     * Whether an answer arrived at all.
     *
     * <p>`role_fit` is the one factor a judge is told to answer even when it is zero, so its
     * absence is not an opinion — it is an unreachable endpoint, a reply that was not JSON,
     * or a model that ignored the instruction. Without this the three cases are
     * indistinguishable from "no penalties applied", and the offer gets a total computed
     * from four of five weights: measured, 63 of 101 scored offers had no judged factor at
     * all and every one of them still carried a number.
     */
    static boolean answered(List<ScoreReason> reasons) {
        return reasons.stream().anyMatch(reason -> "role_fit".equals(reason.factor()));
    }
}
