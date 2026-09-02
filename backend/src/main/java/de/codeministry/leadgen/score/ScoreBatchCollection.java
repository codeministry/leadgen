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
 * What one poll of the submitted batches did.
 *
 * @param ended batches that stopped being in flight, collected and failed alike. Anything
 *     above zero means the shortlist changed and the run that submitted has to be finished:
 *     packaging and the digest were skipped when it ended without its scores.
 * @param scored offers that came back with an answer. An offer whose batch entry errored is
 *     counted in neither, stays unscored, and is judged normally by the next run.
 */
public record ScoreBatchCollection(int ended, int scored) {

    static ScoreBatchCollection nothing() {
        return new ScoreBatchCollection(0, 0);
    }

    public boolean anythingHappened() {
        return ended > 0;
    }
}
