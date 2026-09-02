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
import java.util.Optional;

/**
 * A judge that can be asked about many offers at once and answer later.
 *
 * <p>Separate from {@link Judge} because batching is a property of the wire format, not of
 * the question: the same four bounded factors are asked either way, and
 * {@link ChatClientJudge} still owns the question, the bounds and the reading of an answer.
 * Only
 * the Messages API batch is implemented; a provider without one is simply not a
 * {@code BatchJudge}, and the loader refuses `llm.batch` for it rather than letting the
 * flag be read and ignored.
 *
 * <p><b>Neither method throws.</b> A judge that fails returns nothing, and here that means
 * the offers stay unjudged and due, which the next run picks up. The alternative is a
 * pipeline that stops because a provider had a bad minute.
 */
public interface BatchJudge extends Judge {

    /**
     * @return the provider's id for the batch, or empty when the submission did not happen.
     *     Empty is not a failure worth stopping for: nothing was written, nothing is in
     *     flight, and the offers are still due.
     */
    Optional<String> submit(List<ScoreCandidate> offers);

    /** Never blocks on the batch finishing. Ask again later is one of the three answers. */
    BatchOutcome collect(String batchId);
}
