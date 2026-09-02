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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;

import de.codeministry.leadgen.ingest.ExtractedOffer;
import de.codeministry.leadgen.manual.ManualDocumentName;
import de.codeministry.leadgen.manual.ManualUploadService;
import de.codeministry.leadgen.manual.PendingDocument;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/** The upload endpoint: what it accepts, what it refuses, and with which status. */
@WebMvcTest(ManualSourceController.class)
class ManualSourceControllerTest {

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private ManualUploadService uploads;

    @Test
    void acceptsAMarkdownDocumentAndAnswersWithWhatTheExtractionRead() {
        given(uploads.store(anyString(), any())).willReturn(document());

        assertThat(mvc.post().uri("/api/sources/manual/documents").multipart().file(file("offer.md")))
                .hasStatus(201)
                .bodyJson()
                .extractingPath("$.offer.title")
                .isEqualTo("Senior Java Entwickler (m/w/d)");
    }

    @Test
    void answersARefusedUploadWithTheReason() {
        // A bare 400 is not actionable; "only .md documents are accepted" is.
        willThrow(new ManualDocumentName.Rejected("only .md documents are accepted, not payload.sh"))
                .given(uploads)
                .store(anyString(), any());

        assertThat(mvc.post().uri("/api/sources/manual/documents").multipart().file(file("payload.sh")))
                .hasStatus(400)
                .bodyText()
                .contains(".md");
    }

    @Test
    void answers409WhenNoInboxIsConfiguredAtAll() {
        // Not the client's mistake, and not a 500: the source is switched off.
        willThrow(new ManualUploadService.NoInbox()).given(uploads).pending();

        assertThat(mvc.get().uri("/api/sources/manual/pending")).hasStatus(409);
    }

    @Test
    void confirmsADocumentAndReportsItAsReadyToBeRead() {
        given(uploads.confirm(anyString(), any())).willReturn(document());

        assertThat(mvc.post()
                        .uri("/api/sources/manual/pending/offer.md/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Senior Java Entwickler (m/w/d)\",\"tags\":[\"Java\"]}"))
                .hasStatusOk();
    }

    @Test
    void answers404ForADocumentThatIsNotWaiting() {
        given(uploads.find(anyString())).willReturn(Optional.empty());
        given(uploads.reject(anyString())).willReturn(false);

        assertThat(mvc.get().uri("/api/sources/manual/pending/nothing.md")).hasStatus(404);
        assertThat(mvc.delete().uri("/api/sources/manual/pending/nothing.md")).hasStatus(404);
    }

    private static MockMultipartFile file(String name) {
        return new MockMultipartFile("file", name, MediaType.TEXT_PLAIN_VALUE, "---\ntitle: x\n---\n".getBytes());
    }

    private static PendingDocument document() {
        var offer = new ExtractedOffer(
                "https://portal.example/p/12345",
                "Senior Java Entwickler (m/w/d)",
                "Ablösung eines Monolithen.",
                "https://portal.example/p/12345",
                "Köln",
                null,
                null,
                null,
                List.of("Java"),
                "senior java entwickler",
                // A file dropped in by hand did not come in the post.
                null);
        return new PendingDocument("offer.md", 128, Instant.parse("2026-09-01T10:00:00Z"), "---\n", offer, null, null);
    }
}
