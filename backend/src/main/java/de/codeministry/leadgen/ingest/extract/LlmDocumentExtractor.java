/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.ingest.extract;

import de.codeministry.leadgen.config.model.SourcesConfig.Extraction;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * {@code strategy: llm}: a document with no structure to address at all.
 *
 * <p>The other two strategies read a document the source's rules describe — a block
 * selector and eight field selectors, or a YAML frontmatter. This one exists for the case
 * where there is nothing to describe: a direct enquiry written by a person, one mail, one
 * project, prose. No selector can be written for that, and writing one anyway is how a
 * source ends up extracting whatever happened to sit in the first {@code <div>}.
 *
 * <p><b>One document is one offer here.</b> There is no block splitting, because a mail
 * somebody typed is not a list. A document the model finds no advert in yields nothing,
 * which is the same answer as a newsletter whose selector matched nothing.
 *
 * <p><b>Rules before model still holds, at the level above this one.</b> This is not a
 * fallback that fires when deterministic extraction failed; it is a strategy a source
 * chooses because it never had rules to fall back from. A source with structure keeps
 * {@code html-blocks} and never reaches this class.
 *
 * <p><b>Without a model it yields nothing and says so.</b> {@link ExtractionFallback}
 * answers empty when the configuration reaches no model, and the pass carries on with the
 * other sources. That is the repository's rule rather than a convenience: the tool has to
 * run without a language model, only weaker.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LlmDocumentExtractor {

    private final ExtractionFallback fallback;

    /**
     * The one block this document produces, or none.
     *
     * <p>The reading goes through {@link MarkdownExtractor#unwrapped} for the same reason
     * every other path does, and the reason is not tidiness: a link the model read out of
     * a forwarded newsletter carries the subscriber's address as a query parameter, and it
     * does not become less of an address for having been found by a model.
     */
    public List<Map<String, Object>> extract(String document, Extraction extraction) {
        if (document == null || document.isBlank()) {
            return List.of();
        }
        return fallback.read(document)
                .map(reading -> List.of(MarkdownExtractor.unwrapped(reading.block(), extraction)))
                .orElseGet(() -> {
                    log.info("No offer read out of a free-form document; the source asks for strategy 'llm'");
                    return List.of();
                });
    }
}
