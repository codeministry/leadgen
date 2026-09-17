/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.ingest.extract;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import de.codeministry.leadgen.config.model.PipelineConfig;
import de.codeministry.leadgen.llm.ChatModels;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the extraction fallback sends and what it does with what comes back, at the byte
 * level.
 *
 * <p>No Spring context, the convention every model-facing test here follows: this is about
 * the wire, and a context would only make it slower to find out that a model answered with
 * a link it invented.
 */
class LlmExtractorWireFormatTest {

    private static final WireMockServer MODEL;

    static {
        MODEL = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        MODEL.start();
    }

    /**
     * A pasted advert: no frontmatter, no markup, and the fields spread through the prose.
     */
    private static final String DOCUMENT =
        """
            Senior Java Entwickler (m/w/d)

            Für einen Kunden aus dem Versicherungsumfeld suchen wir ab sofort Unterstützung
            im Bereich Backend-Entwicklung. Einsatzort ist Remote mit gelegentlichen Terminen
            vor Ort.

            Eingestellt am 01.10.2026 von der Beispiel GmbH.
            Mehr dazu unter https://jobs.example.com/p/8831
            """;

    /**
     * Four days after the advert's own date, so a publication date is inside the window and
     * a plausible-looking one in November is not.
     */
    private static final Clock TODAY = Clock.fixed(Instant.parse("2026-10-05T09:00:00Z"), ZoneOffset.UTC);

    @AfterAll
    static void stop() {
        MODEL.stop();
    }

    @BeforeEach
    void reset() {
        MODEL.resetAll();
    }

    @Test
    void sendsTheDocumentItself() {
        // Not an excerpt and not a summary of it: the fields are spread through the prose,
        // and anything cut out here is a field that can only come back as a guess.
        answers(full());

        extractor().read(DOCUMENT);

        MODEL.verify(postRequestedFor(urlPathEqualTo("/chat/completions"))
            .withRequestBody(matching("(?s).*Versicherungsumfeld.*"))
            .withRequestBody(matching("(?s).*jobs.example.com.*")));
    }

    @Test
    void readsTheSevenFieldsAndKeepsTheDocumentAsTheDescription() {
        answers(full());

        var reading = extractor().read(DOCUMENT).orElseThrow();

        assertThat(reading.block())
            .containsEntry(OfferMapper.TITLE, "Senior Java Entwickler (m/w/d)")
            .containsEntry(OfferMapper.LOCATION, "Remote, gelegentlich vor Ort")
            .containsEntry(OfferMapper.AGENCY, "Beispiel GmbH")
            .containsEntry(OfferMapper.URL, "https://jobs.example.com/p/8831")
            .containsEntry(OfferMapper.PUBLISHED, "2026-10-01")
            .containsEntry(OfferMapper.TAGS, java.util.List.of("Java", "Spring Boot"));

        // The advert, not a reading of it. A summary here would be what the enrichment
        // stage, the classifier and the judge all read instead of the advert.
        assertThat((String) reading.block().get(OfferMapper.DESCRIPTION))
            .contains("Versicherungsumfeld")
            .doesNotContain("Zusammenfassung");
    }

    @Test
    void marksWhatTheModelFilledAndNotTheDocument() {
        // The review screen puts a badge on exactly this set, so a reviewer knows which
        // values need checking against the text beside them.
        answers(full());

        var reading = extractor().read(DOCUMENT).orElseThrow();

        assertThat(reading.fromModel())
            .contains(OfferMapper.TITLE, OfferMapper.URL, OfferMapper.PUBLISHED, OfferMapper.TAGS)
            .doesNotContain(OfferMapper.DESCRIPTION);
    }

    @Test
    void dropsAUrlTheDocumentDoesNotContain() {
        // The expensive one: a model that cannot find a link writes a plausible one, and a
        // plausible link on the review screen looks exactly like a real one until it is
        // clicked — by which time it has been confirmed.
        answers(withUrl("\"https://jobs.example.com/p/9999\""));

        var reading = extractor().read(DOCUMENT).orElseThrow();

        assertThat(reading.block()).doesNotContainKey(OfferMapper.URL);
        assertThat(reading.fromModel()).doesNotContain(OfferMapper.URL);
        // The rest of the answer stands: wrong about the link is not wrong about the advert.
        assertThat(reading.block()).containsEntry(OfferMapper.TITLE, "Senior Java Entwickler (m/w/d)");
    }

    @Test
    void dropsSomethingThatIsNotAnAddressAtAll() {
        answers(withUrl("\"jobs.example.com/p/8831\""));

        assertThat(extractor().read(DOCUMENT).orElseThrow().block()).doesNotContainKey(OfferMapper.URL);
    }

    @Test
    void dropsAPublishedDateItCannotQuoteTheDocumentFor() {
        // A date with no line behind it is a date the model inferred, and `published` is
        // what the freshness rule counts days from.
        answers(withPublished("{\"text\":null,\"date\":\"2026-10-01\"}"));
        assertThat(extractor().read(DOCUMENT).orElseThrow().block()).doesNotContainKey(OfferMapper.PUBLISHED);

        answers(withPublished("{\"text\":\"veröffentlicht am 12.09.2026\",\"date\":\"2026-09-12\"}"));
        assertThat(extractor().read(DOCUMENT).orElseThrow().block()).doesNotContainKey(OfferMapper.PUBLISHED);
    }

    @Test
    void dropsAPublishedDateOutsideTheWindow() {
        // Both ends: the epoch date a model answers when it has nothing, and a date in the
        // future, which would stay new for as long as the offer exists.
        answers(withPublished("{\"text\":\"Eingestellt am 01.10.2026\",\"date\":\"1970-01-01\"}"));
        assertThat(extractor().read(DOCUMENT).orElseThrow().block()).doesNotContainKey(OfferMapper.PUBLISHED);

        answers(withPublished("{\"text\":\"Eingestellt am 01.10.2026\",\"date\":\"2026-11-01\"}"));
        assertThat(extractor().read(DOCUMENT).orElseThrow().block()).doesNotContainKey(OfferMapper.PUBLISHED);

        answers(withPublished("{\"text\":\"Eingestellt am 01.10.2026\",\"date\":\"01.10.2026\"}"));
        assertThat(extractor().read(DOCUMENT).orElseThrow().block()).doesNotContainKey(OfferMapper.PUBLISHED);
    }

    @Test
    void acceptsAQuoteThatOnlyDiffersInWhitespace() {
        // A pasted advert carries the line breaks of wherever it was copied from, so a quote
        // and its document disagree about whitespace without disagreeing about anything.
        answers(withPublished("{\"text\":\"Eingestellt am 01.10.2026 von der\\n  Beispiel GmbH\",\"date\":\"2026-10-01\"}"));

        assertThat(extractor().read(DOCUMENT).orElseThrow().block())
            .containsEntry(OfferMapper.PUBLISHED, "2026-10-01");
    }

    @Test
    void answersNothingWithoutATitle() {
        // A document nobody can find a role in is not an advert. An offer with no title is
        // worse than no offer: it reaches the shortlist as a blank row.
        answers(full().replace("\"title\":\"Senior Java Entwickler (m/w/d)\"", "\"title\":null"));

        assertThat(extractor().read(DOCUMENT)).isEmpty();
    }

    @Test
    void tellsAnAnswerApartFromAReplyThatIsNotOne() {
        answers("I could not find an advert in this document.");
        assertThat(extractor().read(DOCUMENT)).isEmpty();

        MODEL.stubFor(post(anyUrl()).willReturn(aResponse().withStatus(500).withBody("{}")));
        assertThat(extractor().read(DOCUMENT)).isEmpty();
    }

    @Test
    void findsTheObjectInsideAFence() {
        answers("""
            Here is what the document says:
            ```json
            %s
            ```
            """.formatted(full()));

        assertThat(extractor().read(DOCUMENT).orElseThrow().block())
            .containsEntry(OfferMapper.TITLE, "Senior Java Entwickler (m/w/d)");
    }

    @Test
    void cutsAValueRatherThanLettingTheAdvertComeBackAsOne() {
        String wall = "x".repeat(500);
        answers(full().replace("\"Senior Java Entwickler (m/w/d)\"", "\"" + wall + "\""));

        assertThat((String) extractor().read(DOCUMENT).orElseThrow().block().get(OfferMapper.TITLE))
            .hasSize(LlmExtractor.MAX_TITLE);
    }

    @Test
    void keepsAtMostTwelveTags() {
        String many = java.util.stream.IntStream.range(0, 30)
            .mapToObj(index -> "\"tag" + index + "\"")
            .collect(java.util.stream.Collectors.joining(","));
        answers(full().replace("[\"Java\",\"Spring Boot\"]", "[" + many + "]"));

        assertThat((java.util.List<?>) extractor().read(DOCUMENT).orElseThrow().block().get(OfferMapper.TAGS))
            .hasSize(LlmExtractor.MAX_TAGS);
    }

    @Test
    void asksForTheDocumentsOwnWordsAndForNoDescription() {
        // Both sentences are load-bearing, and the prompt is where they belong: a stripper
        // on this side would be a pattern guessing at somebody else's prose.
        assertThat(LlmExtractor.instructions())
            .contains("Never translate")
            .contains("Do not write a description")
            .contains("character for character");
    }

    private static String full() {
        return """
            {"title":"Senior Java Entwickler (m/w/d)",
             "url":"https://jobs.example.com/p/8831",
             "location":"Remote, gelegentlich vor Ort",
             "portal":null,
             "agency":"Beispiel GmbH",
             "published":{"text":"Eingestellt am 01.10.2026","date":"2026-10-01"},
             "tags":["Java","Spring Boot"]}
            """;
    }

    private static String withUrl(String url) {
        return full().replace("\"https://jobs.example.com/p/8831\"", url);
    }

    private static String withPublished(String published) {
        return full().replace("{\"text\":\"Eingestellt am 01.10.2026\",\"date\":\"2026-10-01\"}", published);
    }

    private static LlmExtractor extractor() {
        var llm = new PipelineConfig.Llm(
            ChatModels.OPENAI_COMPATIBLE,
            MODEL.baseUrl(),
            "test-key",
            null,
            false,
            new PipelineConfig.Llm.Models(null, "a-model", null, null, null),
            null);
        var chatModel = new ChatModels().of(llm, "a-model");
        return new LlmExtractor(chatModel.orElseThrow(), "a-model", new ObjectMapper(), TODAY);
    }

    private static void answers(String content) {
        MODEL.stubFor(post(urlPathEqualTo("/chat/completions"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(
                    """
                        {"id":"chat-1","object":"chat.completion","created":1,"model":"a-model",
                         "choices":[{"index":0,"finish_reason":"stop",
                                     "message":{"role":"assistant","content":%s}}]}
                        """
                        .formatted(new ObjectMapper().valueToTree(content).toString()))));
    }
}
