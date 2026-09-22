/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import de.codeministry.leadgen.config.model.SourcesConfig;
import de.codeministry.leadgen.config.model.SourcesConfig.Extraction;
import de.codeministry.leadgen.ingest.extract.ExtractionFallback;
import de.codeministry.leadgen.ingest.extract.LlmDocumentExtractor;
import de.codeministry.leadgen.ingest.extract.LlmExtractor;
import de.codeministry.leadgen.ingest.extract.OfferMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * {@code strategy: llm}: the case where there was never anything to select.
 *
 * <p>The model itself is a lambda here. What is under test is the strategy around it —
 * that a reading becomes one offer, that the privacy boundary still applies to a link a
 * model found, and that no model is a working state rather than a failure.
 */
class LlmStrategyTest {

    /** A direct enquiry: one mail, one project, no structure anywhere in it. */
    private static final String PROSE =
            """
            Hallo,

            wir suchen ab Oktober Unterstützung für die Ablösung eines Monolithen,
            Java 21 und Spring Boot, remote mit einem Tag vor Ort in Köln.
            Details unter dem Link unten.

            Viele Grüße
            """;

    private static final String PROXIED =
            "https://tracking.example.com/proxy?target=https%3A%2F%2Fportal.example%2Fp%2F12345&email=someone%40example.com";

    @Test
    void readsOneOfferOutOfProseNobodyCouldHaveWrittenASelectorFor() {
        var extractor = new LlmDocumentExtractor(document -> {
            assertThat(document).isEqualTo(PROSE);
            return Optional.of(reading(Map.of(
                    OfferMapper.TITLE, "Senior Java Entwickler",
                    OfferMapper.URL, PROXIED,
                    OfferMapper.LOCATION, "Köln")));
        });

        var blocks = extractor.extract(PROSE, unwrapping());

        assertThat(blocks).singleElement().satisfies(block -> {
            assertThat(block.get(OfferMapper.TITLE)).isEqualTo("Senior Java Entwickler");
            // The same boundary as every other path: the address does not become less of
            // an address for having been found by a model.
            assertThat((String) block.get(OfferMapper.URL))
                    .isEqualTo("https://portal.example/p/12345")
                    .doesNotContain("email=", "@", "%40");
        });
    }

    @Test
    void yieldsNothingWithoutAModelRatherThanFailingTheRun() {
        var extractor = new LlmDocumentExtractor(ExtractionFallback.none());

        assertThat(extractor.extract(PROSE, unwrapping()))
                .as("no model is a working state; the pass carries on with the other sources")
                .isEmpty();
    }

    /**
     * `single` belongs to `sample-portal-feed`, which names `type: rss` and has no
     * connector in this build either, so the block cannot run whatever the strategy says.
     * It is listed here rather than filtered out, so that a *fourth* strategy nobody
     * implemented fails this test instead of joining a quiet crowd.
     */
    private static final Set<String> SHIPPED_BUT_NOT_DISPATCHED = Set.of("single");

    @Test
    void everyStrategyTheShippedConfigurationNamesIsOneTheRunDispatchesOn() {
        // The failure this catches is a typo rather than a gap: a strategy spelled one way
        // in `sources.yaml` and another in the code is logged as "not implemented yet",
        // which reads exactly like a feature nobody has built.
        Set<String> configured = shipped().sources().stream()
                .map(source -> source.extraction().strategy())
                .filter(strategy -> strategy != null && !strategy.isBlank())
                .collect(java.util.stream.Collectors.toSet());

        Set<String> known = new java.util.HashSet<>(IngestService.IMPLEMENTED_STRATEGIES);
        known.addAll(SHIPPED_BUT_NOT_DISPATCHED);

        assertThat(configured).isNotEmpty().isSubsetOf(known);
        assertThat(configured)
                .as("the strategy this test is about reaches the dispatch")
                .contains("llm");
    }

    private static LlmExtractor.Reading reading(Map<String, Object> block) {
        return new LlmExtractor.Reading(new LinkedHashMap<>(block), Set.copyOf(block.keySet()));
    }

    /**
     * Only the one field matters here: the extraction the source would carry, with the
     * proxy parameter named the way the shipped configuration names it.
     */
    private static Extraction unwrapping() {
        return shipped().sources().stream()
                .map(SourcesConfig.Source::extraction)
                .filter(extraction -> extraction.fields() != null
                        && extraction.fields().get(OfferMapper.URL) != null
                        && extraction.fields().get(OfferMapper.URL).unwrapQueryParam() != null)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "the shipped sources.yaml no longer has a source unwrapping a proxy link"));
    }

    /**
     * The shipped file itself rather than a fixture, so a default that drifts fails here.
     * Read raw: the placeholders are not resolved and nothing in this test needs them.
     */
    private static SourcesConfig shipped() {
        JsonMapper mapper = JsonMapper.builder(new YAMLFactory())
                .addModule(new JavaTimeModule())
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
        try (InputStream in = LlmStrategyTest.class.getResourceAsStream("/leadgen/sources.yaml")) {
            String yaml = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            return mapper.readValue(neutralised(yaml), SourcesConfig.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** `${PLACEHOLDER}` and `${PLACEHOLDER:default}` both become an empty scalar. */
    private static String neutralised(String yaml) {
        Matcher matcher = Pattern.compile("\\$\\{[^}]*}").matcher(yaml);
        return matcher.replaceAll(Matcher.quoteReplacement(""));
    }
}
