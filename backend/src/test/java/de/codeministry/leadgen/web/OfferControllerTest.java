/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.web;

import de.codeministry.leadgen.archive.ArchiveRequest;
import de.codeministry.leadgen.archive.ArchiveResult;
import de.codeministry.leadgen.archive.ArchiveService;
import de.codeministry.leadgen.offer.*;
import de.codeministry.leadgen.score.ScoringService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

/**
 * The archive endpoint: the one thing about an offer a person owns.
 */
@WebMvcTest(OfferController.class)
class OfferControllerTest {

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private OfferQueryService offers;

    @MockitoBean
    private ScoringService scoring;

    @MockitoBean
    private ArchiveService archive;

    @Test
    void answersWithTheWholeEntryRatherThanWithNothing() {
        // The browser replaces its row with what the server stored. A 204 would leave it
        // patching its own copy, which then disagrees until the next reload.
        given(archive.setArchived(anyLong(), anyBoolean())).willReturn(true);
        given(offers.find(1L)).willReturn(Optional.of(entry(Instant.parse("2026-09-02T08:00:00Z"), "MANUAL")));

        assertThat(mvc.patch()
                        .uri("/api/offers/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"archived\":true}"))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.offer.archiveSource")
                .isEqualTo("MANUAL");
    }

    @Test
    void restoresWithTheSameEndpointAndTheOtherValue() {
        given(archive.setArchived(anyLong(), anyBoolean())).willReturn(true);
        given(offers.find(1L)).willReturn(Optional.of(entry(null, "RESTORED")));

        assertThat(mvc.patch()
                        .uri("/api/offers/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"archived\":false}"))
                .hasStatusOk();

        then(archive).should().setArchived(1L, false);
    }

    @Test
    void answers404ForAnOfferThatIsNotThere() {
        given(archive.setArchived(anyLong(), anyBoolean())).willReturn(false);

        assertThat(mvc.patch()
                        .uri("/api/offers/999")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"archived\":true}"))
                .hasStatus(404);
    }

    @Test
    void refusesAPatchThatSaysNothing() {
        // `Boolean` rather than `boolean`, so an absent field is a validation failure
        // naming the field instead of a Jackson error naming the type.
        assertThat(mvc.patch()
                        .uri("/api/offers/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .hasStatus4xxClientError();
    }

    @Test
    void archivesEveryIdInOneRequestRatherThanOnePerOffer() {
        given(archive.setArchived(anyCollection(), anyBoolean())).willReturn(new ArchiveResult(3, 3, 0));

        assertThat(mvc.post()
            .uri("/api/offers/archive")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"ids\":[1,2,3]}"))
            .hasStatusOk();

        then(archive).should().setArchived(List.of(1L, 2L, 3L), true);
    }

    @Test
    void answersWithACountRatherThanWithTheRows() {
        // The list drops an archived row instead of replacing it, so entries here would be
        // fetched only to be discarded — and a page of them is measured in megabytes.
        given(archive.setArchived(anyCollection(), anyBoolean())).willReturn(new ArchiveResult(2, 2, 1));

        assertThat(mvc.post()
            .uri("/api/offers/archive")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"ids\":[1,2]}"))
            .hasStatusOk()
            .bodyJson()
            .extractingPath("$.archived")
            .isEqualTo(2);

        then(offers).should(never()).find(anyLong());
    }

    @Test
    void doesNotAnswer404ForAnIdThatNamesNoOffer() {
        // The counterpart to `answers404ForAnOfferThatIsNotThere`, and the pair is the point:
        // one offer asked about is a question with an honest "no such offer"; a set operation
        // must not lose forty-nine decisions because one member has gone.
        given(archive.setArchived(anyCollection(), anyBoolean())).willReturn(new ArchiveResult(3, 2, 0));

        assertThat(mvc.post()
            .uri("/api/offers/archive")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"ids\":[1,2,999]}"))
            .hasStatusOk()
            .bodyJson()
            .extractingPath("$.requested")
            .isEqualTo(3);
    }

    @Test
    void refusesARequestThatNamesNoOffer() {
        // A write that asks for nothing is a question, not a success. Both shapes: an empty
        // list, and a body with no list at all — the second one reaches the compact
        // constructor with null before `@NotEmpty` ever runs.
        assertThat(mvc.post()
            .uri("/api/offers/archive")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"ids\":[]}"))
            .hasStatus4xxClientError();

        assertThat(mvc.post()
            .uri("/api/offers/archive")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
            .hasStatus4xxClientError();

        then(archive).should(never()).setArchived(anyCollection(), anyBoolean());
    }

    @Test
    void refusesMoreOffersThanTheCeiling() {
        // Refused and never narrowed: silently archiving the first 500 of 501 is a wrong
        // write with no symptom.
        String ids = LongStream.rangeClosed(1, ArchiveRequest.MAX_IDS + 1)
            .mapToObj(Long::toString)
            .collect(Collectors.joining(","));

        assertThat(mvc.post()
            .uri("/api/offers/archive")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"ids\":[" + ids + "]}"))
            .hasStatus4xxClientError();

        then(archive).should(never()).setArchived(anyCollection(), anyBoolean());
    }

    @Test
    void collapsesADuplicateIdBeforeItReachesTheService() {
        given(archive.setArchived(anyCollection(), anyBoolean())).willReturn(new ArchiveResult(2, 2, 0));

        assertThat(mvc.post()
            .uri("/api/offers/archive")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"ids\":[1,1,2]}"))
            .hasStatusOk();

        then(archive).should().setArchived(List.of(1L, 2L), true);
    }

    private static ShortlistEntry entry(Instant archivedAt, String source) {
        var offer = new OfferView(
                1L,
                "x",
                "Senior Java Entwickler",
                "Beschreibung",
                "https://example.invalid/1",
                "Köln",
                "portal-a",
                null,
                null,
                List.of(),
                null,
                null,
                null,
                null,
                null,
                "de",
                null,
                null,
                archivedAt,
                source);
        return new ShortlistEntry(
            offer,
            new OfferScoreView(88, true, List.of(), null, null),
            new OfferFlags(false, true),
            List.of(),
            List.of());
    }
}
