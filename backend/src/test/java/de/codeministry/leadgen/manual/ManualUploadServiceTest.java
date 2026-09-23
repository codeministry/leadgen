/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.manual;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.codeministry.leadgen.Databases;
import de.codeministry.leadgen.ingest.extract.ExtractionFallback;
import de.codeministry.leadgen.ingest.extract.LlmExtractor;
import de.codeministry.leadgen.ingest.extract.OfferMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The review queue: an upload waits in `pending/` until somebody confirms it, and the file
 * is the only state there is.
 */
@SpringBootTest
@Testcontainers
class ManualUploadServiceTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = Databases.postgres();

    /**
     * A model that reads whatever it is handed. It stands in for a configured endpoint,
     * which this test has none of — and it only ever fires for a document with no
     * frontmatter, so nothing else in this class changes because it is here.
     */
    @TestConfiguration
    static class AModelThatReadsTheDocument {

        /**
         * How often the model was asked, which is the whole point of the reading cache.
         */
        static final java.util.concurrent.atomic.AtomicInteger ASKED = new java.util.concurrent.atomic.AtomicInteger();

        @Bean
        @Primary
        ExtractionFallback fallback() {
            return document -> {
                ASKED.incrementAndGet();
                return reading(document);
            };
        }

        private static Optional<LlmExtractor.Reading> reading(String document) {
            return Optional.of(new LlmExtractor.Reading(
                    new LinkedHashMap<>(Map.of(
                            OfferMapper.TITLE,
                            "Java-Entwickler",
                            OfferMapper.LOCATION,
                            "Remote",
                            OfferMapper.DESCRIPTION,
                            document)),
                    Set.of(OfferMapper.TITLE, OfferMapper.LOCATION)));
        }
    }

    private static final String PASTED = "Wir suchen ab sofort einen Java-Entwickler. Remote möglich.";

    private static final String DOCUMENT = """
        ---
        title: Senior Java Entwickler (m/w/d)
        url: https://portal.example/p/12345
        location: Köln
        ---
        Ablösung eines Monolithen.
        """;

    static Path configDirectory;

    @DynamicPropertySource
    static void configuration(DynamicPropertyRegistry registry) {
        registry.add("leadgen.config-dir", () -> configDir().toString());
    }

    @Autowired
    private ManualUploadService uploads;

    @Autowired
    private ManualInbox inbox;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void emptyTheInbox() throws Exception {
        jdbc.update("DELETE FROM offer");
        jdbc.update("DELETE FROM source");
        for (Path directory :
                List.of(inbox.pending().orElseThrow(), inbox.inbox().orElseThrow())) {
            try (var files = Files.list(directory)) {
                files.filter(Files::isRegularFile).forEach(file -> file.toFile().delete());
            }
        }
    }

    @Test
    void putsAnUploadWhereNoSourceReadsIt() {
        var stored = uploads.store("gefunden-auf-linkedin.md", DOCUMENT.getBytes(StandardCharsets.UTF_8));

        assertThat(stored.name()).isEqualTo("gefunden-auf-linkedin.md");
        assertThat(inbox.pending().orElseThrow().resolve(stored.name())).exists();
        // The source globs the inbox itself, never the subdirectory the review sits in.
        assertThat(inbox.inbox().orElseThrow().resolve(stored.name())).doesNotExist();
    }

    @Test
    void showsWhatTheExtractionMakesOfItBeforeAnythingEnters() {
        var stored = uploads.store("offer.md", DOCUMENT.getBytes(StandardCharsets.UTF_8));

        assertThat(stored.offer()).isNotNull();
        assertThat(stored.offer().title()).isEqualTo("Senior Java Entwickler (m/w/d)");
        assertThat(stored.offer().location()).isEqualTo("Köln");
        assertThat(stored.text()).contains("Ablösung eines Monolithen.");
    }

    @Test
    void marksTheFieldsAModelReadAndLeavesTheOthersUnmarked() {
        // The review screen badges exactly this set. A frontmatter document carries none,
        // because its values are the document's own words and there is nothing to check.
        var byTheRules = uploads.store("frontmatter.md", DOCUMENT.getBytes(StandardCharsets.UTF_8));
        assertThat(byTheRules.fromModel()).isEmpty();

        var read = uploads.store("gepasted.md", PASTED.getBytes(StandardCharsets.UTF_8));

        assertThat(read.offer()).isNotNull();
        assertThat(read.offer().title()).isEqualTo("Java-Entwickler");
        assertThat(read.fromModel()).containsExactlyInAnyOrder(OfferMapper.TITLE, OfferMapper.LOCATION);
        // The description is the document itself, whichever path read it, so it is never
        // marked as something a reviewer has to check against the text beside it.
        assertThat(read.fromModel()).doesNotContain(OfferMapper.DESCRIPTION);
        assertThat(read.offer().description()).contains("Remote möglich");
    }

    @Test
    void asksTheModelOncePerVersionOfAFile() {
        // Measured against a real endpoint: one reading took about a minute, and listing the
        // queue reads every document in it. Without this the review screen pays for a model
        // call per pasted advert on every request — and a model asked twice does not answer
        // identically, so the list, the panel and the confirm would each show a different
        // reading of the same unchanged file.
        AModelThatReadsTheDocument.ASKED.set(0);
        uploads.store("gepasted.md", PASTED.getBytes(StandardCharsets.UTF_8));

        uploads.pending();
        uploads.pending();
        uploads.find("gepasted.md");

        assertThat(AModelThatReadsTheDocument.ASKED.get()).isEqualTo(1);
    }

    @Test
    void readsAgainWhenTheFileItselfChanged() throws Exception {
        // The file is still the state. Editing the document on disk has to produce a fresh
        // reading, or the cache would be a second copy of the truth.
        AModelThatReadsTheDocument.ASKED.set(0);
        uploads.store("gepasted.md", PASTED.getBytes(StandardCharsets.UTF_8));
        uploads.pending();

        Path file = inbox.pending().orElseThrow().resolve("gepasted.md");
        Files.writeString(file, PASTED + " Zweite Fassung.");
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 2000));

        uploads.pending();

        assertThat(AModelThatReadsTheDocument.ASKED.get()).isEqualTo(2);
    }

    @Test
    void writesNoProvenanceIntoTheConfirmedDocument() {
        // What lands in the inbox is the eight-field contract and nothing else: by then the
        // values have been through a person, and a marker in the file would travel into the
        // archive as a field nobody reads.
        uploads.store("gepasted.md", PASTED.getBytes(StandardCharsets.UTF_8));

        uploads.confirm(
                "gepasted.md",
                new ManualOfferFields("Java-Entwickler", null, PASTED, "Remote", null, null, null, List.of()));

        Path moved = inbox.inbox().orElseThrow().resolve("gepasted.md");
        assertThat(moved).content().doesNotContain("fromModel").doesNotContain("llm");
    }

    @Test
    void namesTheOfferAlreadyInThePipelineBeforeTheConfirmAndNotAfter() {
        long sourceId = jdbc.queryForObject(
                "INSERT INTO source (name, kind) VALUES ('portal-a', 'rss') RETURNING id", Long.class);
        jdbc.update("""
            INSERT INTO offer (source_id, external_id, title, fingerprint, status)
            VALUES (?, 'x', 'Senior Java Entwickler (m/w/d)', 'senior java entwickler', 'INGESTED')
            """, sourceId);

        var stored = uploads.store("offer.md", DOCUMENT.getBytes(StandardCharsets.UTF_8));

        assertThat(stored.duplicateOfId()).isNotNull();
        assertThat(stored.duplicateOfTitle()).isEqualTo("Senior Java Entwickler (m/w/d)");
    }

    @Test
    void writesTheCorrectionIntoTheFileAndMovesItWhereTheSourceReads() throws Exception {
        uploads.store("offer.md", DOCUMENT.getBytes(StandardCharsets.UTF_8));

        var confirmed = uploads.confirm(
                "offer.md",
                new ManualOfferFields(
                        "Senior Java Entwickler, korrigiert",
                        "https://portal.example/p/12345",
                        "Ablösung eines Monolithen, Java 21.",
                        "Köln",
                        "LinkedIn",
                        "Beispiel GmbH",
                        "2026-09-01",
                        List.of("Java", "Spring Boot")));

        Path moved = inbox.inbox().orElseThrow().resolve("offer.md");
        assertThat(moved).exists();
        assertThat(inbox.pending().orElseThrow().resolve("offer.md")).doesNotExist();
        // The correction lives in the document, so re-reading the same file later produces
        // the same offer — there is no second copy of the truth in a table.
        assertThat(Files.readString(moved))
                .contains("Senior Java Entwickler, korrigiert")
                .contains("LinkedIn");
        assertThat(confirmed.offer().title()).isEqualTo("Senior Java Entwickler, korrigiert");
        assertThat(confirmed.offer().tags()).containsExactly("Java", "Spring Boot");
    }

    @Test
    void aRejectedUploadLeavesNothingBehind() {
        uploads.store("offer.md", DOCUMENT.getBytes(StandardCharsets.UTF_8));

        assertThat(uploads.reject("offer.md")).isTrue();
        assertThat(uploads.pending()).isEmpty();
        assertThat(uploads.reject("offer.md")).isFalse();
    }

    @Test
    void refusesAnythingButAMarkdownDocument() {
        assertThatThrownBy(() -> uploads.store("payload.sh", "rm -rf /".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(ManualDocumentName.Rejected.class)
                .hasMessageContaining(".md");
    }

    @Test
    void refusesAFileLargerThanAnAdvertCouldBe() {
        byte[] large = new byte[(int) ManualUploadService.MAX_BYTES + 1];

        assertThatThrownBy(() -> uploads.store("offer.md", large))
                .isInstanceOf(ManualDocumentName.Rejected.class)
                .hasMessageContaining("limit");
    }

    @Test
    void cannotBeMadeToWriteOutsideTheInbox() {
        // The directory part is dropped rather than cleaned: a name is a name, and the
        // only reason an upload carries a path is that someone wants it somewhere else.
        var stored = uploads.store("../../etc/passwd.md", DOCUMENT.getBytes(StandardCharsets.UTF_8));
        assertThat(stored.name()).isEqualTo("passwd.md");

        var windows = uploads.store("..\\..\\windows\\notes.md", DOCUMENT.getBytes(StandardCharsets.UTF_8));
        assertThat(windows.name()).isEqualTo("notes.md");

        // A stem that is nothing but dots would resolve to the directory itself.
        var dots = uploads.store("...md", DOCUMENT.getBytes(StandardCharsets.UTF_8));
        assertThat(dots.name()).isEqualTo("offer.md");

        Path pending = inbox.pending().orElseThrow();
        assertThat(uploads.pending())
                .extracting(PendingDocument::name)
                .containsExactlyInAnyOrder("passwd.md", "notes.md", "offer.md");
        assertThat(pending.resolve("passwd.md")).exists();
        assertThat(Path.of("/etc/passwd.md")).doesNotExist();
    }

    private static Path configDir() {
        if (configDirectory == null) {
            try {
                configDirectory = Files.createTempDirectory("leadgen-manual");
                configDirectory.toFile().deleteOnExit();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
        return configDirectory;
    }
}
