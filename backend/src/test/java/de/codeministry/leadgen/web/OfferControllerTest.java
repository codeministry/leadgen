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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import de.codeministry.leadgen.archive.ArchiveRequest;
import de.codeministry.leadgen.archive.ArchiveResult;
import de.codeministry.leadgen.archive.ArchiveService;
import de.codeministry.leadgen.ask.AdvertAnswer;
import de.codeministry.leadgen.ask.AdvertAskService;
import de.codeministry.leadgen.ask.AdvertQuestion;
import de.codeministry.leadgen.enrich.OfferRefetch;
import de.codeministry.leadgen.offer.*;
import de.codeministry.leadgen.score.ScoringService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

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

    @MockitoBean
    private AdvertAskService asks;

    @MockitoBean
    private OfferRefetch refetch;

    @Test
    void answersWithTheWholeEntryRatherThanWithNothing() {
        // The browser replaces its row with what the server stored. A 204 would leave it
        // patching its own copy, which then disagrees until the next reload.
        given(archive.setArchived(anyLong(), anyBoolean())).willReturn(true);
        given(offers.find(1L)).willReturn(Optional.of(entry(Instant.parse("2026-09-02T08:00:00Z"), "MANUAL")));

        var body = assertThat(mvc.patch()
                        .uri("/api/v1/offers/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"archived\":true}"))
                .hasStatusOk()
                .bodyJson();
        body.extractingPath("$.offer.archiveSource").isEqualTo("MANUAL");
        // When this tool read the row, which is not what the advert says about itself.
        // Serialised as an instant and not as a timestamp, because the screen formats it
        // with the same pipe as every other day on that panel.
        body.extractingPath("$.offer.ingestedAt").asString().startsWith("2026-09-01T05:00");
    }

    @Test
    void restoresWithTheSameEndpointAndTheOtherValue() {
        given(archive.setArchived(anyLong(), anyBoolean())).willReturn(true);
        given(offers.find(1L)).willReturn(Optional.of(entry(null, "RESTORED")));

        assertThat(mvc.patch()
                        .uri("/api/v1/offers/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"archived\":false}"))
                .hasStatusOk();

        then(archive).should().setArchived(1L, false);
    }

    @Test
    void answers404ForAnOfferThatIsNotThere() {
        given(archive.setArchived(anyLong(), anyBoolean())).willReturn(false);

        assertThat(mvc.patch()
                        .uri("/api/v1/offers/999")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"archived\":true}"))
                .hasStatus(404);
    }

    @Test
    void refusesAPatchThatSaysNothing() {
        // `Boolean` rather than `boolean`, so an absent field is a validation failure
        // naming the field instead of a Jackson error naming the type.
        assertThat(mvc.patch()
                        .uri("/api/v1/offers/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .hasStatus4xxClientError();
    }

    @Test
    void archivesEveryIdInOneRequestRatherThanOnePerOffer() {
        given(archive.setArchived(anyCollection(), anyBoolean())).willReturn(new ArchiveResult(3, 3, 0));

        assertThat(mvc.post()
                        .uri("/api/v1/offers/archive")
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
                        .uri("/api/v1/offers/archive")
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
                        .uri("/api/v1/offers/archive")
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
                        .uri("/api/v1/offers/archive")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[]}"))
                .hasStatus4xxClientError();

        assertThat(mvc.post()
                        .uri("/api/v1/offers/archive")
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
                        .uri("/api/v1/offers/archive")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[" + ids + "]}"))
                .hasStatus4xxClientError();

        then(archive).should(never()).setArchived(anyCollection(), anyBoolean());
    }

    @Test
    void collapsesADuplicateIdBeforeItReachesTheService() {
        given(archive.setArchived(anyCollection(), anyBoolean())).willReturn(new ArchiveResult(2, 2, 0));

        assertThat(mvc.post()
                        .uri("/api/v1/offers/archive")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[1,1,2]}"))
                .hasStatusOk();

        then(archive).should().setArchived(List.of(1L, 2L), true);
    }

    @Test
    void refusesASortNobodyDefinedWithoutEverReachingTheQuery() {
        // The injection test belongs here, at the edge, because this is where a string stops
        // being a string: the enum is the allowlist, so a name nobody defined never composes
        // an ORDER BY.
        assertThat(mvc.get().uri("/api/v1/offers").param("sort", "score; DROP TABLE offer"))
                .hasStatus(org.springframework.http.HttpStatus.BAD_REQUEST);

        then(offers).should(never()).shortlist(any());
    }

    @Test
    void refusesAStartWindowNobodyDefined() {
        assertThat(mvc.get().uri("/api/v1/offers").param("startWindow", "yesterday"))
                .hasStatus(org.springframework.http.HttpStatus.BAD_REQUEST);

        then(offers).should(never()).shortlist(any());
    }

    @Test
    void asksTheAdvertTheQuestionThatWasNamed() {
        given(asks.ask(42L, AdvertQuestion.RATE))
                .willReturn(java.util.Optional.of(
                        new AdvertAnswer("rate", true, "95 EUR.", "Die Vergütung liegt bei 95 EUR.", "a-model")));

        assertThat(mvc.post().uri("/api/v1/offers/42/ask").param("question", "rate"))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.quote")
                .isEqualTo("Die Vergütung liegt bei 95 EUR.");
    }

    @Test
    void refusesAQuestionNobodyDefinedWithoutEverReachingTheModel() {
        // A question the server silently replaced would answer about something the reader did
        // not ask, and the answer would look exactly as authoritative.
        assertThat(mvc.post().uri("/api/v1/offers/42/ask").param("question", "salary"))
                .hasStatus(org.springframework.http.HttpStatus.BAD_REQUEST);

        then(asks).should(never()).ask(anyLong(), any());
    }

    @Test
    void answers409WhenNobodyCouldAskAtAll() {
        // Not an empty answer and not a silent one: no model, no fetched text and a spent
        // budget are all "nobody could ask", which the screen must not draw like a quiet advert.
        given(asks.ask(anyLong(), any())).willReturn(java.util.Optional.empty());

        assertThat(mvc.post().uri("/api/v1/offers/42/ask").param("question", "onsite"))
                .hasStatus(org.springframework.http.HttpStatus.CONFLICT)
                .bodyText()
                .contains("budget");
    }

    @Test
    void passesTheRelatednessAxisThroughAsTheTypesItIs() {
        given(offers.shortlist(any())).willReturn(new ShortlistPage(List.of(), null, 0, 0, 0, List.of(), null, null));

        assertThat(mvc.get().uri("/api/v1/offers").param("similar", "42")).hasStatusOk();

        var captured = org.mockito.ArgumentCaptor.forClass(ShortlistQuery.class);
        then(offers).should().shortlist(captured.capture());
        assertThat(captured.getValue().related().similarTo()).isEqualTo(42L);
        assertThat(captured.getValue().related().semantic()).isNull();
    }

    @Test
    void refusesBothSpellingsOfTheRelatednessAxisBeforeReadingAnything() {
        // The same shape the score axis has: two spellings of one narrowing is not "both at
        // once", because the server would have to pick a neighbourhood around two different
        // points and the caller could not tell which one it got.
        assertThat(mvc.get()
                        .uri("/api/v1/offers")
                        .param("semantic", "kubernetes")
                        .param("similar", "42"))
                .hasStatus(org.springframework.http.HttpStatus.BAD_REQUEST);

        then(offers).should(never()).shortlist(any());
    }

    @Test
    void answers400WhenTheInstallationCannotSearchByMeaning() {
        // Never a silently widened list: a shared link or a saved view carrying `semantic=` is
        // how this arrives on an installation without the index, and the reader has to be able
        // to tell a refusal from a quiet market.
        given(offers.shortlist(any()))
                .willThrow(new de.codeministry.leadgen.retrieval.SemanticFilter.RetrievalUnavailable(
                        "this installation does not search by meaning: the retrieval index is switched off"));

        assertThat(mvc.get().uri("/api/v1/offers").param("semantic", "kubernetes"))
                .hasStatus(org.springframework.http.HttpStatus.BAD_REQUEST)
                .bodyText()
                .contains("does not search by meaning");
    }

    @Test
    void passesTheNewFiltersThroughToTheQueryAsTheTypesTheyAre() {
        given(offers.shortlist(any())).willReturn(new ShortlistPage(List.of(), null, 0, 0, 0, List.of(), null, null));

        assertThat(mvc.get()
                        .uri("/api/v1/offers")
                        .param("sort", "deadline")
                        .param("startWindow", "soon")
                        .param("minMonths", "6")
                        .param("deadlineOpen", "true"))
                .hasStatusOk();

        var captured = org.mockito.ArgumentCaptor.forClass(ShortlistQuery.class);
        then(offers).should().shortlist(captured.capture());
        assertThat(captured.getValue().sort()).isEqualTo(ShortlistSort.DEADLINE);
        assertThat(captured.getValue().startWindow()).isEqualTo(StartWindow.SOON);
        assertThat(captured.getValue().minMonths()).isEqualTo(6);
        assertThat(captured.getValue().deadlineOpen()).isTrue();
    }

    @Test
    void refusesAScoreStateNobodyDefined() {
        assertThat(mvc.get().uri("/api/v1/offers").param("scoreState", "pending"))
                .hasStatus(org.springframework.http.HttpStatus.BAD_REQUEST);

        then(offers).should(never()).shortlist(any());
    }

    @Test
    void refusesTwoSpellingsOfTheScoreAxisWithoutEverReachingTheQuery() {
        // Intersected instead of refused this returns rows, and a short list after filtering
        // is indistinguishable from a quiet day on the market. The edge is where it has to be
        // caught, because nothing further in reads the request as a request.
        assertThat(mvc.get().uri("/api/v1/offers").param("band", "shortlist").param("minScore", "60"))
                .hasStatus(org.springframework.http.HttpStatus.BAD_REQUEST);

        then(offers).should(never()).shortlist(any());
    }

    @Test
    void passesTheScoreAxisAndEveryNamedPortalThroughAsTheTypesTheyAre() {
        given(offers.shortlist(any())).willReturn(new ShortlistPage(List.of(), null, 0, 0, 0, List.of(), null, null));

        assertThat(mvc.get()
                        .uri("/api/v1/offers")
                        .param("minScore", "40")
                        .param("maxScore", "80")
                        .param("portal", "portal-b", "portal-c"))
                .hasStatusOk();

        var captured = org.mockito.ArgumentCaptor.forClass(ShortlistQuery.class);
        then(offers).should().shortlist(captured.capture());
        assertThat(captured.getValue().score().min()).isEqualTo(40);
        assertThat(captured.getValue().score().max()).isEqualTo(80);
        assertThat(captured.getValue().portals()).containsExactly("portal-b", "portal-c");
    }

    @Test
    void readsOnePortalAsAListOfOne() {
        // The parameter kept its singular name, so a link written before the filter took more
        // than one still binds — this is what says so.
        given(offers.shortlist(any())).willReturn(new ShortlistPage(List.of(), null, 0, 0, 0, List.of(), null, null));

        assertThat(mvc.get().uri("/api/v1/offers").param("portal", "portal-c")).hasStatusOk();

        var captured = org.mockito.ArgumentCaptor.forClass(ShortlistQuery.class);
        then(offers).should().shortlist(captured.capture());
        assertThat(captured.getValue().portals()).containsExactly("portal-c");
    }

    @Test
    void defaultsToTheScoreOrderWhenNothingAsksForAnything() {
        given(offers.shortlist(any())).willReturn(new ShortlistPage(List.of(), null, 0, 0, 0, List.of(), null, null));

        assertThat(mvc.get().uri("/api/v1/offers")).hasStatusOk();

        var captured = org.mockito.ArgumentCaptor.forClass(ShortlistQuery.class);
        then(offers).should().shortlist(captured.capture());
        assertThat(captured.getValue().sort()).isEqualTo(ShortlistSort.SCORE);
        assertThat(captured.getValue().startWindow()).isEqualTo(StartWindow.ANY);
        assertThat(captured.getValue().minMonths()).isNull();
    }

    private static ShortlistEntry entry(Instant archivedAt, String source) {
        var offer = new OfferView(
                1L,
                "sample-newsletter",
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
                null,
                null,
                null,
                null,
                "de",
                null,
                null,
                Instant.parse("2026-09-01T05:00:00Z"),
                archivedAt,
                source,
                null);
        return new ShortlistEntry(
                offer,
                new OfferScoreView(88, true, List.of(), null, null),
                new OfferFlags(false, true, false),
                List.of(),
                List.of());
    }
}
