/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.web;

import java.util.List;

/**
 * The models the run may be scored with, as the header reads them.
 *
 * <p>A list from the server rather than a constant in the browser, for the same reason
 * nothing in the browser names a weight, a filter stage or a source type: a second copy
 * disagrees with the configuration the first time a model is added, and the symptom would
 * be a select box offering something the server refuses.
 *
 * @param available every configured model, the default first. Empty means no judge is
 *     configured at all, which is a legitimate state — the pipeline still runs and the
 *     shortlist stays unscored — and the select has nothing to offer, so it is not shown.
 * @param preferred the model a run uses when none is chosen. Always the first entry of
 *     {@code available} while that list is not empty, named separately so the browser does
 *     not have to know that.
 */
record ScoringModels(List<String> available, String preferred) {

    static ScoringModels of(List<String> choices) {
        return new ScoringModels(choices, choices.isEmpty() ? null : choices.getFirst());
    }
}
