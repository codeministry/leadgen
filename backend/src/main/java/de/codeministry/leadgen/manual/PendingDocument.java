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
import java.util.List;

/**
 * One uploaded document waiting for review, with what the extraction made of it.
 *
 * @param offer            null when nothing could be read from the file at all. That is not an error, it
 *                         is the case the review screen exists for: a pasted ad has no frontmatter, and
 *                         when the source asks for no fallback or no model answers, the operator fills the
 *                         fields in.
 * @param fromModel        the field names in {@code offer} a language model read, empty whenever the
 *                         frontmatter was readable. The review screen marks exactly these, so a reviewer
 *                         knows which values to check against the text beside them instead of checking all
 *                         of them equally. It is not written into the file on confirm: what lands there is
 *                         the eight-field contract and nothing else, and by then the values have been
 *                         through a person.
 * @param duplicateOfTitle the offer already in the pipeline that carries the same
 *                         normalized title, or null. Answered before the confirm rather than after, because
 *                         "you already have this" is only useful while there is still a decision to make.
 */
public record PendingDocument(
        String name,
        long size,
        Instant uploadedAt,
        String text,
        ExtractedOffer offer,
        List<String> fromModel,
        Long duplicateOfId,
        String duplicateOfTitle) {}
