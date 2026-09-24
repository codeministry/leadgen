/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.codeministry.leadgen.Databases;
import de.codeministry.leadgen.config.ConfigFixtures;
import de.codeministry.leadgen.llm.ChatModels;
import de.codeministry.leadgen.llm.LlmBudget;
import de.codeministry.leadgen.packaging.PackagingService;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The letter in the offer detail view, through the endpoint, against a real database and a real
 * package folder.
 *
 * <p>ISC-259: saving an edit rewrites {@code cover_letter.txt} and the stored copy, so the
 * package download carries the edited text. ISC-257, the edited half: the row and
 * {@code meta.json} name the author {@code edited}. ISC-260: a regenerate spends one budget call
 * and replaces the letter; once the application went out, both writes answer 409 and the file
 * stays as it was.
 *
 * <p>{@link ChatModels} and {@link LlmBudget} are mocks, so no test here can reach a real model
 * whatever the developer's {@code .env} says. The packages are built by {@link PackagingService}
 * itself, without a writing model, so each test starts from the template letter a real build
 * leaves behind.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class CoverLetterControllerTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = Databases.postgres();

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String EDIT = "Sehr geehrte Damen und Herren,\n\n"
            + "diesen Satz habe ich selbst geschrieben, Umlaute inklusive: äöüß.\n\n"
            + "Mit freundlichen Grüßen\n";

    /** A draft the guard accepts for the advert {@link #requested} writes. */
    private static final String DRAFT = """
            {"salutation": "Sehr geehrte Damen und Herren,",
             "body": "Ihre Anzeige sucht Spring Boot. Damit arbeite ich täglich, zuletzt im Beispielprojekt.",
             "skills": ["Spring Boot"], "projects": ["Beispielprojekt"]}
            """;

    /** Created once: a {@code @DynamicPropertySource} supplier may run more than once. */
    private static Path configDirectory;

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private PackagingService packaging;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private ChatModels chatModels;

    @MockitoBean
    private LlmBudget budget;

    private long sourceId;

    @DynamicPropertySource
    static void configuration(DynamicPropertyRegistry registry) {
        registry.add("leadgen.config-dir", () -> config().toString());
    }

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM offer_score_reason");
        jdbc.update("DELETE FROM application");
        jdbc.update("DELETE FROM offer");
        jdbc.update("DELETE FROM source");
        sourceId =
                jdbc.queryForObject("INSERT INTO source (name, kind) VALUES ('test', 'file') RETURNING id", Long.class);
        given(budget.take()).willReturn(true);
    }

    @Test
    void savingAnEditRewritesTheLetterThePackageDownloadCarries() throws IOException {
        long id = packaged();

        assertThat(mvc.put()
                        .uri("/api/v1/offers/{id}/cover-letter", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of("text", EDIT))))
                .hasStatusOk()
                .bodyJson()
                .satisfies(body -> {
                    assertThat(body).extractingPath("$.text").isEqualTo(EDIT);
                    assertThat(body).extractingPath("$.author").isEqualTo("edited");
                    assertThat(body).extractingPath("$.at").isNotNull();
                });

        assertThat(zipEntry(id, "cover_letter.txt"))
                .as("the download carries the edit")
                .isEqualTo(EDIT);
        assertThat(jdbc.queryForMap("SELECT cover_letter_text, cover_letter_author FROM offer WHERE id = ?", id))
                .containsEntry("cover_letter_text", EDIT)
                .containsEntry("cover_letter_author", "edited");
        assertThat(JSON.readTree(zipEntry(id, "meta.json"))
                        .path("cover_letter")
                        .path("author")
                        .asText())
                .isEqualTo("edited");
        assertThat(mvc.get().uri("/api/v1/offers/{id}/cover-letter", id))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.text")
                .isEqualTo(EDIT);
    }

    @Test
    void readsTheStoredLetterAndItsAuthor() {
        long id = packaged();

        assertThat(mvc.get().uri("/api/v1/offers/{id}/cover-letter", id))
                .hasStatusOk()
                .bodyJson()
                .satisfies(body -> {
                    assertThat(body).extractingPath("$.text").isEqualTo(letterFile(id));
                    assertThat(body).extractingPath("$.author").isEqualTo("template");
                });
    }

    @Test
    void readsTheFileOfAPackageBuiltBeforeTheLetterWasStored() {
        long id = packaged();
        jdbc.update(
                "UPDATE offer SET cover_letter_text = NULL, cover_letter_author = NULL, cover_letter_at = NULL"
                        + " WHERE id = ?",
                id);

        assertThat(mvc.get().uri("/api/v1/offers/{id}/cover-letter", id))
                .hasStatusOk()
                .bodyJson()
                .satisfies(body -> {
                    assertThat(body).extractingPath("$.text").isEqualTo(letterFile(id));
                    assertThat(body).extractingPath("$.author").isEqualTo("template");
                    assertThat(body).extractingPath("$.at").isNotNull();
                });
    }

    @Test
    void answersNotFoundForAnOfferWithoutAPackage() {
        long id = offer();

        assertThat(mvc.get().uri("/api/v1/offers/{id}/cover-letter", id)).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(mvc.put()
                        .uri("/api/v1/offers/{id}/cover-letter", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"x\"}"))
                .hasStatus(HttpStatus.NOT_FOUND);
        assertThat(mvc.post().uri("/api/v1/offers/{id}/cover-letter/draft", id)).hasStatus(HttpStatus.NOT_FOUND);
        verify(budget, never()).take();
    }

    @Test
    void refusesABlankLetter() {
        long id = packaged();

        assertThat(mvc.put()
                        .uri("/api/v1/offers/{id}/cover-letter", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"  \"}"))
                .hasStatus(HttpStatus.BAD_REQUEST);
    }

    /** ISC-260: one budget call, a new text, written to the file and the row. */
    @Test
    void regeneratingReplacesTheLetterAtOneBudgetCall() {
        long id = packaged();
        String before = letterFile(id);
        AtomicInteger calls = new AtomicInteger();
        given(chatModels.writing(any())).willReturn(Optional.of(answering(DRAFT, calls)));

        MvcTestResult result =
                mvc.post().uri("/api/v1/offers/{id}/cover-letter/draft", id).exchange();

        assertThat(result).hasStatusOk().bodyJson().extractingPath("$.author").isEqualTo("model");
        assertThat(calls).hasValue(1);
        verify(budget, times(1)).take();
        String after = letterFile(id);
        assertThat(after).isNotEqualTo(before).contains("zuletzt im Beispielprojekt");
        assertThat(result).bodyJson().extractingPath("$.text").isEqualTo(after);
        assertThat(jdbc.queryForMap("SELECT cover_letter_text, cover_letter_author FROM offer WHERE id = ?", id))
                .containsEntry("cover_letter_text", after)
                .containsEntry("cover_letter_author", "model");
    }

    /** A regenerate after an edit replaces the edit: the person asked for it. */
    @Test
    void regeneratingReplacesAnEditedLetter() throws IOException {
        long id = packaged();
        save(id, EDIT);
        given(chatModels.writing(any())).willReturn(Optional.of(answering(DRAFT, new AtomicInteger())));

        assertThat(mvc.post().uri("/api/v1/offers/{id}/cover-letter/draft", id))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.author")
                .isEqualTo("model");
        assertThat(letterFile(id)).isNotEqualTo(EDIT);
    }

    /** Without a writing model the template writes the fresh letter, and no call is spent. */
    @Test
    void regeneratingWithoutAWritingModelWritesTheTemplate() throws IOException {
        long id = packaged();
        save(id, EDIT);

        assertThat(mvc.post().uri("/api/v1/offers/{id}/cover-letter/draft", id))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.author")
                .isEqualTo("template");
        assertThat(letterFile(id)).startsWith("Sehr geehrte Damen und Herren,").isNotEqualTo(EDIT);
        verify(budget, never()).take();
    }

    /** ISC-260, the spent budget: 429, and neither the file nor the row changes. */
    @Test
    void answersTooManyRequestsWhenTheBudgetIsSpentAndWritesNothing() {
        long id = packaged();
        String before = letterFile(id);
        Map<String, Object> row = letterRow(id);
        AtomicInteger calls = new AtomicInteger();
        given(chatModels.writing(any())).willReturn(Optional.of(answering(DRAFT, calls)));
        given(budget.take()).willReturn(false);

        assertThat(mvc.post().uri("/api/v1/offers/{id}/cover-letter/draft", id))
                .hasStatus(HttpStatus.TOO_MANY_REQUESTS);

        assertThat(calls).hasValue(0);
        assertThat(letterFile(id)).isEqualTo(before);
        assertThat(letterRow(id)).isEqualTo(row);
    }

    /** ISC-260: at SENT both writes are refused, and the file is the one that went out. */
    @Test
    void refusesBothWritesOnceTheApplicationIsSent() throws IOException {
        long id = packaged();
        save(id, EDIT);
        jdbc.update("UPDATE application SET status = 'SENT' WHERE offer_id = ?", id);
        AtomicInteger calls = new AtomicInteger();
        given(chatModels.writing(any())).willReturn(Optional.of(answering(DRAFT, calls)));

        assertRefused(id, calls);
    }

    /**
     * Sent and later lost is still sent: the event log says the letter went out, and the current
     * status does not undo that. The same reading the archive keeps a folder by.
     */
    @Test
    void refusesBothWritesForAnApplicationThatWasSentAndHasMovedOn() throws IOException {
        long id = packaged();
        save(id, EDIT);
        Long application = jdbc.queryForObject("SELECT id FROM application WHERE offer_id = ?", Long.class, id);
        jdbc.update("UPDATE application SET status = 'LOST' WHERE id = ?", application);
        jdbc.update(
                "INSERT INTO application_event (application_id, from_status, to_status) VALUES (?, 'PACKAGED', 'SENT')",
                application);
        AtomicInteger calls = new AtomicInteger();
        given(chatModels.writing(any())).willReturn(Optional.of(answering(DRAFT, calls)));

        assertRefused(id, calls);
    }

    /**
     * Sent, archived, restored: the restore puts the application back at NEW and keeps the folder
     * and the letter of an application that went out. The status says nothing was sent; the letter
     * in the folder is still the one the client received, so both writes stay refused.
     */
    @Test
    void refusesBothWritesAfterASentApplicationIsArchivedAndRestored() throws IOException {
        long id = packaged();
        save(id, EDIT);
        Long application = jdbc.queryForObject("SELECT id FROM application WHERE offer_id = ?", Long.class, id);
        assertThat(mvc.patch()
                        .uri("/api/v1/applications/{id}", application)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"SENT\"}"))
                .hasStatusOk();
        assertThat(mvc.patch()
                        .uri("/api/v1/offers/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"archived\":true}"))
                .hasStatusOk();
        assertThat(mvc.patch()
                        .uri("/api/v1/offers/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"archived\":false}"))
                .hasStatusOk();
        assertThat(jdbc.queryForObject("SELECT status FROM application WHERE id = ?", String.class, application))
                .as("the restore reset the status")
                .isEqualTo("NEW");
        AtomicInteger calls = new AtomicInteger();
        given(chatModels.writing(any())).willReturn(Optional.of(answering(DRAFT, calls)));

        assertRefused(id, calls);
    }

    private void assertRefused(long id, AtomicInteger calls) {
        Map<String, Object> row = letterRow(id);

        assertThat(mvc.put()
                        .uri("/api/v1/offers/{id}/cover-letter", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"a later thought\"}"))
                .hasStatus(HttpStatus.CONFLICT);
        assertThat(mvc.post().uri("/api/v1/offers/{id}/cover-letter/draft", id)).hasStatus(HttpStatus.CONFLICT);

        assertThat(letterFile(id)).isEqualTo(EDIT);
        assertThat(letterRow(id)).isEqualTo(row);
        assertThat(calls).hasValue(0);
        verify(budget, never()).take();
    }

    private void save(long id, String text) throws IOException {
        assertThat(mvc.put()
                        .uri("/api/v1/offers/{id}/cover-letter", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of("text", text))))
                .hasStatusOk();
    }

    private Map<String, Object> letterRow(long id) {
        return jdbc.queryForMap(
                "SELECT cover_letter_text, cover_letter_author, cover_letter_at FROM offer WHERE id = ?", id);
    }

    private String letterFile(long id) {
        try {
            return Files.readString(folderOf(id).resolve("cover_letter.txt"), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Path folderOf(long id) {
        return Path.of(jdbc.queryForObject("SELECT package_dir FROM offer WHERE id = ?", String.class, id));
    }

    /** One entry of the package as the download endpoint serves it. */
    private String zipEntry(long id, String name) throws IOException {
        MvcTestResult result = mvc.get().uri("/api/v1/offers/{id}/package", id).exchange();
        assertThat(result).hasStatusOk();
        byte[] zip = result.getResponse().getContentAsByteArray();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                if (entry.getName().endsWith("/" + name) || entry.getName().equals(name)) {
                    return new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        throw new AssertionError("the package has no " + name);
    }

    /** An offer with a PACKAGED application and the folder a build leaves behind. */
    private long packaged() {
        long id = offer();
        jdbc.update("INSERT INTO application (offer_id, status) VALUES (?, 'PACKAGED')", id);
        assertThat(packaging.buildFor(id).built()).isEqualTo(1);
        return id;
    }

    private long offer() {
        return jdbc.queryForObject("""
            INSERT INTO offer (source_id, external_id, title, description, url, fingerprint, status,
                               score_value, score_band, location, portal, agency, published_on)
            VALUES (?, ?, 'Senior Java Entwickler (m/w/d)', 'Wir suchen einen Entwickler mit Spring Boot.',
                    'https://example.invalid/projekt/1', 'fp', 'PASSED', 88, 'SHORTLISTED',
                    'Köln', 'portal-a', 'Acme Consulting GmbH', DATE '2026-08-31')
            RETURNING id
            """, Long.class, sourceId, "ext-" + System.nanoTime());
    }

    /** A chat model that answers every prompt with the same text and counts the calls. */
    private static ChatModel answering(String text, AtomicInteger calls) {
        return new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                calls.incrementAndGet();
                return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
            }
        };
    }

    private static synchronized Path config() {
        if (configDirectory != null) {
            return configDirectory;
        }
        try {
            Path dir = Files.createTempDirectory("leadgen-cover-letter-config");
            dir.toFile().deleteOnExit();
            Path packages = Files.createTempDirectory("leadgen-cover-letter-packages");
            packages.toFile().deleteOnExit();
            ConfigFixtures.materialize(dir);
            Path pipeline = dir.resolve("pipeline.yaml");
            Files.writeString(
                    pipeline,
                    Files.readString(pipeline, StandardCharsets.UTF_8)
                            .replace("output_dir: ${PACKAGES_DIR:./packages}", "output_dir: " + packages),
                    StandardCharsets.UTF_8);
            configDirectory = dir;
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
