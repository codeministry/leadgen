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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
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
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    private static final String DOCUMENT =
            """
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
    void namesTheOfferAlreadyInThePipelineBeforeTheConfirmAndNotAfter() {
        long sourceId = jdbc.queryForObject(
                "INSERT INTO source (name, kind) VALUES ('portal-a', 'rss') RETURNING id", Long.class);
        jdbc.update(
                """
                INSERT INTO offer (source_id, external_id, title, fingerprint, status)
                VALUES (?, 'x', 'Senior Java Entwickler (m/w/d)', 'senior java entwickler', 'INGESTED')
                """,
                sourceId);

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
