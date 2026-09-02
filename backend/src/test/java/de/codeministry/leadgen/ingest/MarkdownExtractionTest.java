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

import de.codeministry.leadgen.config.ConfigFixtures;
import de.codeministry.leadgen.config.ConfigProperties;
import de.codeministry.leadgen.config.model.SourcesConfig;
import de.codeministry.leadgen.ingest.connector.FileSourceConnector;
import de.codeministry.leadgen.ingest.extract.MarkdownExtractor;
import de.codeministry.leadgen.ingest.extract.OfferMapper;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The manual entry path: one Markdown file is one offer, read deterministically.
 *
 * <p>The rules come from the shipped `manual-inbox` block, not from a fixture of its own,
 * so a broken default fails the build rather than the first upload.
 */
class MarkdownExtractionTest {

    private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = FACTORY.getValidator();

    private static final String COMPLETE =
            """
            ---
            title: Senior Java Entwickler Spring Boot (m/w/d)
            url: https://tracking.example.com/proxy?target=https%3A%2F%2Fportal.example%2Fp%2F12345&email=someone%40example.com
            location: Köln
            portal: portal-a
            agency: Acme Consulting GmbH
            published: 2026-09-01
            tags: [Java, Spring Boot, Kafka]
            ---
            Ablösung eines Monolithen, Java 21, Spring Boot, Kafka.
            """;

    @TempDir
    Path configDir;

    @TempDir
    Path inbox;

    @AfterAll
    static void closeFactory() {
        FACTORY.close();
    }

    @Test
    void readsEveryFieldOfTheFrontmatterAndTheBodyAsTheDescription() {
        var offer = only(COMPLETE);

        assertThat(offer.title()).isEqualTo("Senior Java Entwickler Spring Boot (m/w/d)");
        assertThat(offer.location()).isEqualTo("Köln");
        assertThat(offer.portal()).isEqualTo("portal-a");
        assertThat(offer.agency()).isEqualTo("Acme Consulting GmbH");
        assertThat(offer.publishedOn()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(offer.tags()).containsExactly("Java", "Spring Boot", "Kafka");
        assertThat(offer.description()).isEqualTo("Ablösung eines Monolithen, Java 21, Spring Boot, Kafka.");
    }

    @Test
    void unwrapsAProxyLinkPastedOutOfTheNewsletter() {
        // The document arrived by hand, which changes nothing: the address must not reach
        // the archive whatever route the text took to get here.
        var offer = only(COMPLETE);

        assertThat(offer.url()).isEqualTo("https://portal.example/p/12345");
        assertThat(offer.url()).doesNotContain("email=").doesNotContain("@").doesNotContain("%40");
    }

    @Test
    void acceptsTagsAsTheCommaSeparatedLineSomeoneTypedInstead() {
        var offer = only(
                """
                ---
                title: Angular Entwickler
                tags: Angular, TypeScript , RxJS
                ---
                Frontend für ein Versicherungsportal.
                """);

        assertThat(offer.tags()).containsExactly("Angular", "TypeScript", "RxJS");
    }

    @Test
    void identifiesAnOfferWithoutAUrlByItsContent() {
        // The upsert is on (source_id, external_id). Without this the same ad uploaded
        // twice is two offers, and deduplication would have to clean up after it.
        String ad =
                """
                ---
                title: Kubernetes Platform Engineer
                ---
                k3s, ArgoCD, Traefik.
                """;

        var first = only(ad);
        var second = only(ad);

        assertThat(first.externalId()).isNotNull().startsWith("sha256:");
        assertThat(second.externalId()).isEqualTo(first.externalId());
    }

    @Test
    void readsNoOfferFromAFileThatIsNothingButAPastedAd() {
        // No frontmatter, so nothing deterministic to read. `fallback: llm` is what this
        // case is for; until that exists the file stays where it is rather than entering
        // the pipeline as an offer with no title.
        assertThat(extract("Wir suchen ab sofort einen Java-Entwickler in Köln."))
                .isEmpty();
    }

    @Test
    void ignoresAThematicBreakInTheBody() {
        var offer = only(
                """
                ---
                title: Java Entwickler
                ---
                Erste Zeile.

                ---

                Zweite Zeile.
                """);

        assertThat(offer.title()).isEqualTo("Java Entwickler");
        assertThat(offer.description()).contains("Erste Zeile.").contains("Zweite Zeile.");
    }

    private ExtractedOffer only(String document) {
        var offers = extract(document);
        assertThat(offers).hasSize(1);
        return offers.getFirst();
    }

    private List<ExtractedOffer> extract(String document) {
        write(document);
        SourcesConfig.Source source = manualInbox();
        var documents = new FileSourceConnector(new ConfigProperties(configDir.toString())).read(source, 0L);
        assertThat(documents).hasSize(1);

        var extractor = new MarkdownExtractor();
        var mapper = new OfferMapper();
        return extractor.extract(documents.getFirst().html(), source.extraction()).stream()
                .map(block -> mapper.map(block, source.extraction(), null))
                .filter(offer -> offer.title() != null && !offer.title().isBlank())
                .toList();
    }

    private SourcesConfig.Source manualInbox() {
        ConfigFixtures.materialize(configDir);
        var snapshot = ConfigFixtures.loaderFor(
                        configDir,
                        VALIDATOR,
                        Map.of("MANUAL_INBOX_DIR", inbox.toAbsolutePath().toString()))
                .load();
        return snapshot.sources().sources().stream()
                .filter(s -> s.id().equals("manual-inbox"))
                .findFirst()
                .orElseThrow();
    }

    private void write(String document) {
        try {
            Files.writeString(inbox.resolve("offer.md"), document, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
