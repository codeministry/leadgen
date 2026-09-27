/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.chat.suggest;

import java.util.Optional;

/**
 * One rule that decides whether the data supports a question. Reads a screen's read service and
 * never writes; an empty answer means the line was not met, and the set simply has one fewer.
 */
public interface SuggestionTrigger {

    /** The trigger's key, one of the constants on {@link SuggestionService}. */
    String key();

    Optional<SuggestionCandidate> evaluate(SuggestionScope scope, SuggestionThresholds thresholds);
}
