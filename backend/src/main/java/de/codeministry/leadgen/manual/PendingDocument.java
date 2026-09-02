/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.manual;

import de.codeministry.leadgen.ingest.ExtractedOffer;
import java.time.Instant;

/**
 * One uploaded document waiting for review, with what the extraction made of it.
 *
 * @param offer null when the file has no frontmatter at all. That is not an error, it is
 *     the case the review screen exists for: a pasted ad has nothing deterministic to
 *     read, and the operator fills the fields in.
 * @param duplicateOfTitle the offer already in the pipeline that carries the same
 *     normalized title, or null. Answered before the confirm rather than after, because
 *     "you already have this" is only useful while there is still a decision to make.
 */
public record PendingDocument(
        String name,
        long size,
        Instant uploadedAt,
        String text,
        ExtractedOffer offer,
        Long duplicateOfId,
        String duplicateOfTitle) {}
