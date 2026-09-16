/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.ingest.extract;

import java.util.Optional;

/**
 * Where {@link MarkdownExtractor} gets the model it may ask when the deterministic rules
 * found nothing to read.
 *
 * <p>An interface with one method, for one reason: the deterministic half of this package
 * must stay testable without a configuration and without a model. {@link LlmExtractors} is
 * the bean Spring injects; a test about frontmatter passes {@link #none()} and proves that
 * nothing is asked, and a test about the wiring passes a lambda.
 *
 * <p>The method is the <em>reading</em> and not the extractor that does it, which is what
 * keeps each reason for an empty answer in the class that knows it: a configuration that
 * reaches no model is {@link LlmExtractors}'s to report, and a document the model found no
 * advert in is {@link LlmExtractor}'s.
 */
@FunctionalInterface
public interface ExtractionFallback {

    /**
     * What a model reads out of a document the deterministic rules could not, or nothing.
     *
     * <p>Nothing is a working state and always has been: a document with no frontmatter and
     * no model is the case that shipped, and it is left where it is.
     */
    Optional<LlmExtractor.Reading> read(String document);

    /**
     * No model at all, which is the deterministic half of this package on its own.
     */
    static ExtractionFallback none() {
        return document -> Optional.empty();
    }
}
