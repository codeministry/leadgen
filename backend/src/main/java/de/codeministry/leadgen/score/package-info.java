/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */

/**
 * What an offer is worth, and why.
 *
 * <p>Rules before model. {@code RuleScorer} decides everything the profile and the offer's own
 * fields can decide, for free. A {@code Judge} is asked about role fit and three penalties, and
 * nothing else.
 *
 * <p><b>Unscored is not zero, and not nothing.</b> With no judge the deterministic reasons are
 * still written; what is withheld is the total, because a number from five of nine weights is
 * not comparable to one from all nine, and the same offer would otherwise score differently
 * depending on whether a key happened to be configured that morning.
 *
 * <p><b>The weight table decides, not the answer.</b> A factor the model invents is dropped and
 * a model awarding itself 900 points gets what the table says. The bounds live in one method
 * and the clamp in one other, so the synchronous and the batched path cannot disagree — a
 * shortlist whose halves clamp differently is not a ranking.
 *
 * <p>A judge that fails returns nothing rather than throwing, and a run judges only what is
 * stale. Three things make a score stale, and they are the three it is comparable within:
 * never written, a different ruleset, a different model. Two judges are two scales, and the
 * shortlist threshold is one number read against both.
 */
package de.codeministry.leadgen.score;
