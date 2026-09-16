/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.ingest;

import de.codeministry.leadgen.config.ConfigFixtures;
import de.codeministry.leadgen.config.ConfigProperties;
import de.codeministry.leadgen.config.model.SourcesConfig;
import de.codeministry.leadgen.ingest.connector.FileSourceConnector;
import de.codeministry.leadgen.ingest.extract.HtmlBlockExtractor;
import de.codeministry.leadgen.ingest.extract.OfferMapper;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two search-agent tables, against the mails they were written for.
 *
 * <p><b>This test reads the operator's own configuration, not the shipped defaults.</b> The
 * selectors name a portal, and a portal does not belong in a committed file — so the tables
 * live in {@code config/sources.yaml} and the mails in {@code docs/samples/emails-*}, both
 * gitignored, and this test skips itself when either is absent. That is the same arrangement
 * {@link SampleCorpusAcceptanceTest} already has for the aggregator, and for the same reason:
 * {@link ExtractionTest} is what covers the mechanics on a fresh clone and in CI.
 *
 * <p>What it is here to catch is a selector that has stopped matching. Nothing else can:
 * a block that yields no title is dropped in silence, a field whose selector misses is null,
 * and both look exactly like a quiet week on the market.
 */
class SearchAgentCorpusTest {

    private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = FACTORY.getValidator();

    @AfterAll
    static void closeFactory() {
        FACTORY.close();
    }

    /**
     * The placeholders this test fills in, and nothing else.
     *
     * <p><b>Deliberately not the developer's `.env`.</b> An enabled IMAP source fails the whole
     * config load without credentials, so the load needs three values — but reading them from
     * the file would make the test's behaviour depend on whose machine it runs on, which is
     * the trap `ScoringWithoutAModelTest` already paid for. Nothing here opens a connection:
     * both sources under test are `type: file`, and the corpus paths come in the same way.
     */
    private static Map<String, String> environment() {
        Path root = ConfigFixtures.repositoryRoot();
        return Map.of(
                "IMAP_HOST", "imap.invalid",
                "IMAP_USER", "nobody",
                "IMAP_PASSWORD", "unused",
                "FREELANCE_DE_INBOX", root.resolve("docs/samples/emails-freelance-de").toString(),
                "FREELANCERMAP_INBOX", root.resolve("docs/samples/emails-freelancermap").toString());
    }

    /** Every offer the named file source extracts from its own corpus directory. */
    private static List<ExtractedOffer> offersOf(String sourceId) {
        Path config = ConfigFixtures.repositoryRoot().resolve("config");
        Assumptions.assumeTrue(
                Files.isRegularFile(config.resolve("sources.yaml")),
                "config/sources.yaml is absent — the operator's tables are gitignored");

        SourcesConfig sources = ConfigFixtures.loaderFor(config, VALIDATOR, environment())
                .load()
                .sources();
        SourcesConfig.Source source = sources.sources().stream()
                .filter(s -> s.id().equals(sourceId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no source '" + sourceId + "' in config/sources.yaml"));

        Path corpus = Path.of(source.path());
        Assumptions.assumeTrue(Files.isDirectory(corpus), corpus + " is absent — the mails are gitignored");

        var extractor = new HtmlBlockExtractor();
        var mapper = new OfferMapper();
        List<ExtractedOffer> offers = new ArrayList<>();
        for (RawDocument document :
                new FileSourceConnector(new ConfigProperties(config.toString())).read(source, 0L)) {
            extractor.extract(document.html(), source.extraction()).stream()
                    .map(block -> mapper.map(block, source.extraction(), null))
                    .forEach(offers::add);
        }
        return offers;
    }

    @Test
    void readsEveryCardOutOfTheFreelanceDeMails() {
        List<ExtractedOffer> offers = offersOf("freelance-de-eml");

        // 9, 11 and 6 across the three saved mails. The number matters because the child
        // combinator in the block selector is the whole reason it is right: the same selector
        // without it matched 46, 56 and 31 — every ancestor table of every card.
        assertThat(offers).hasSize(26);
        assertThat(offers).allSatisfy(offer -> {
            assertThat(offer.title()).isNotBlank();
            assertThat(offer.url()).contains("/projekte/projekt-");
            assertThat(offer.portal()).isEqualTo("freelance.de");
            assertThat(offer.agency()).isNotBlank();
        });
    }

    @Test
    void takesTheMetaLineApartWhereverTheLocationEnds() {
        List<ExtractedOffer> offers = offersOf("freelance-de-eml");

        // One paragraph, `agency <br> location`, and `text()` folds the break into a space.
        // Three shapes occur and the regex has to survive all three; the third is the one a
        // greedy pattern gets wrong, by handing back an empty agency.
        assertThat(offers).anySatisfy(offer -> {
            assertThat(offer.agency()).isEqualTo("softwareXperts GmbH");
            assertThat(offer.location()).isEqualTo("A-1010 Wien (Wien)");
        });
        assertThat(offers).anySatisfy(offer -> {
            assertThat(offer.agency()).isEqualTo("grandega GmbH");
            assertThat(offer.location()).isEqualTo("Remote (Deutschland)");
        });
        assertThat(offers).anySatisfy(offer -> {
            assertThat(offer.agency()).isEqualTo("Contractor Consulting GmbH");
            assertThat(offer.location()).isNull();
        });
        // An agency that never loses its tail to the location pattern.
        assertThat(offers).anySatisfy(offer ->
                assertThat(offer.agency()).isEqualTo("NEO - Professional Solutions GmbH"));
    }

    @Test
    void readsTheLabelledFieldsOutOfTheFreelancermapCard() {
        List<ExtractedOffer> offers = offersOf("freelancermap-eml");

        // Two mails, one project each, exactly as their subjects announce.
        assertThat(offers).hasSize(2);
        assertThat(offers).allSatisfy(offer -> {
            assertThat(offer.portal()).isEqualTo("freelancermap.de");
            // Three labels share one element, so a prefix read would hand back the other two.
            assertThat(offer.location()).doesNotContain("Vertragsart").doesNotContain("//");
            // The date format describes the whole value. Get it wrong and this is null, with
            // nothing but a debug line to say so.
            assertThat(offer.publishedOn()).isNotNull();
            // `utm_*` is tracking, and an unrecognised parameter costs the whole query.
            assertThat(offer.url()).contains("/nproj/").doesNotContain("?");
        });
        assertThat(offers).anySatisfy(offer -> {
            assertThat(offer.title()).isEqualTo("Cloud Solution Architect (m/w/d)");
            assertThat(offer.agency()).isEqualTo("AWESOME! Software GmbH");
            assertThat(offer.location()).isEqualTo("remote");
        });
    }

    @Test
    void neitherPortalStatesADescriptionAndThatIsTheMeasurement() {
        // The reason the hard filter judges these two on the title alone. If a description
        // ever appears, this fails and the filter's reach is worth rethinking.
        assertThat(offersOf("freelance-de-eml")).allSatisfy(offer -> assertThat(offer.description()).isNull());
        assertThat(offersOf("freelancermap-eml")).allSatisfy(offer -> assertThat(offer.description()).isNull());
    }
}
