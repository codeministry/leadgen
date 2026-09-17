/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.retrieval;

/**
 * What one retrieval-indexing pass did.
 *
 * @param due      offers whose advert has no current retrieval vector: never embedded, embedded
 *                 by a different model, or segmented again since the vector was written.
 * @param embedded offers that got one. <b>Lower than {@code due} is the number to watch and not
 *                 a fault.</b> It means the day's {@code llm.budget} ran out mid-pass, the rest
 *                 stayed due, and the next run continues where this one stopped. On the first
 *                 pass after the stage is switched on it will be lower for several nights, which
 *                 is the backfill and is documented behaviour.
 * @param requests requests that actually left for a model. One per batch of adverts, and one
 *                 call against the budget whatever the batch carried.
 * @param model    the embedding model that answered, or null when none is configured — in which
 *                 case the column stays empty and semantic search does not exist, which is what
 *                 a fresh clone does.
 */
public record RetrievalReport(int due, int embedded, int requests, String model) {

    /** Nothing to do, or nothing this installation can do. */
    public static RetrievalReport skipped() {
        return new RetrievalReport(0, 0, 0, null);
    }
}
