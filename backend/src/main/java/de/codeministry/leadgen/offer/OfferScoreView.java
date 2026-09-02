/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.offer;

import de.codeministry.leadgen.score.ScoreReason;
import java.util.List;

/**
 * The score as the screen shows it.
 *
 * @param value null when the pipeline ran without a language model. The reasons are still
 *     there — what is withheld is the total, because one computed from five of the nine
 *     weights is not comparable to one computed from all nine.
 */
public record OfferScoreView(
        Integer value, boolean hardPass, List<ScoreReason> reasons, String model, String rulesetVersion) {}
