/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.fields;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import de.codeministry.leadgen.config.model.PipelineConfig;
import de.codeministry.leadgen.llm.ChatModels;
import de.codeministry.leadgen.offer.ShortlistSort;
import java.time.LocalDate;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * What the field extractor sends and what it does with what comes back, at the byte level.
 *
 * <p>No Spring context, the convention {@code ContentClassifierWireFormatTest} and {@code
 * JudgeWireFormatTest} both follow: this is about the wire, and a context would only make it
 * slower to find out that a model answered with prose.
 */
class FieldExtractorWireFormatTest {

    private static final WireMockServer MODEL;

    static {
        MODEL = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        MODEL.start();
    }

    private static final FieldExtractor.Candidate OFFER = new FieldExtractor.Candidate(
            42,
            "Senior Java Entwickler (m/w/d)",
            "Kurzbeschreibung aus dem Newsletter.",
            "Wir suchen ab sofort für zunächst 6 Monate mit Option auf Verlängerung.",
            LocalDate.of(2026, 10, 1),
            "6");

    @AfterAll
    static void stop() {
        MODEL.stop();
    }

    @BeforeEach
    void reset() {
        MODEL.resetAll();
    }

    @Test
    void sendsTheAdvertAndWhatTheApplicationAlreadyKnowsAboutIt() {
        // The second half is the point: a model told to find a start date while the row
        // beside it already states one knows less than the application does, and the
        // correction it is asked for cannot be made against a value it cannot see.
        answers("""
            {"start":{"text":"ab sofort","date":null},
             "duration":{"text":"6 Monate","months":6},
             "deadline":{"text":null,"date":null}}
            """);

        extractor().extract(OFFER);

        MODEL.verify(postRequestedFor(urlPathEqualTo("/chat/completions"))
                .withRequestBody(matching("(?s).*Senior Java Entwickler.*"))
                .withRequestBody(matching("(?s).*Option auf Verl.*"))
                .withRequestBody(matching("(?s).*start: 2026-10-01.*")));
    }

    @Test
    void keepsThePhraseAndTheResolvedValueSideBySide() {
        answers("""
            {"start":{"text":"ab sofort","date":null},
             "duration":{"text":"6 Monate mit Option auf Verlängerung","months":6},
             "deadline":{"text":"Bewerbungen bis 30.09.2026","date":"2026-09-30"}}
            """);

        ExtractedFields fields = extractor().extract(OFFER).orElseThrow();

        assertThat(fields.startText()).isEqualTo("ab sofort");
        assertThat(fields.startsOn()).isNull();
        assertThat(fields.durationMonths()).isEqualTo(6);
        assertThat(fields.applyBy()).isEqualTo(LocalDate.of(2026, 9, 30));
        assertThat(fields.isEmpty()).isFalse();
    }

    @Test
    void treatsAnAdvertThatStatesNothingAsAnAnswer() {
        // Most adverts state no deadline at all. "Nothing" is a finished decision and has to
        // stay distinguishable from "the model did not answer", which leaves the offer due.
        answers("""
            {"start":{"text":null,"date":null},
             "duration":{"text":null,"months":null},
             "deadline":{"text":null,"date":null}}
            """);

        assertThat(extractor().extract(OFFER))
                .isPresent()
                .get()
                .extracting(ExtractedFields::isEmpty)
                .isEqualTo(true);
    }

    @Test
    void asksForTheValueAndNotTheRow() {
        // Measured on the live data: two of six start phrases came back as "Start: 01.10.2026"
        // and two of six durations as "Laufzeit: 12 Monate", and the card prints its own label
        // in front of whatever it is given — so the screen read "Start Start: 01.10.2026".
        // The prompt is where this belongs; a stripper in the browser would be a pattern
        // guessing at somebody else's prose.
        assertThat(FieldExtractor.instructions())
                .contains("is the value, not the row")
                .contains("printed twice");
    }

    @Test
    void dropsAValueItCannotQuoteTheAdvertFor() {
        // A date with no phrase is a date the model inferred. Kept, it would be the one field
        // on the screen nobody can check against the advert beside it.
        answers("""
            {"start":{"text":null,"date":"2026-11-01"},
             "duration":{"text":null,"months":12},
             "deadline":{"text":null,"date":"2026-10-01"}}
            """);

        ExtractedFields fields = extractor().extract(OFFER).orElseThrow();

        assertThat(fields.isEmpty()).isTrue();
    }

    @ParameterizedTest
    @MethodSource("outOfBounds")
    void dropsAValueOutsideTheBoundsAndKeepsTheRest(String duration, String deadline) {
        answers("""
                {"start":{"text":"ab sofort","date":null},
                 "duration":{"text":"lange","months":%s},
                 "deadline":{"text":"bald","date":%s}}
                """.formatted(duration, deadline));

        ExtractedFields fields = extractor().extract(OFFER).orElseThrow();

        assertThat(fields.durationMonths()).isNull();
        assertThat(fields.applyBy()).isNull();
        // The phrases survive: a model that is wrong about one value is not wrong about the
        // advert, and the quote is still what a person reads.
        assertThat(fields.startText()).isEqualTo("ab sofort");
        assertThat(fields.durationText()).isEqualTo("lange");
    }

    static Stream<Arguments> outOfBounds() {
        return Stream.of(
                Arguments.of("0", "\"1970-01-01\""),
                Arguments.of("999", "\"Q4/2026\""),
                Arguments.of("-3", "\"01.10.2026\""),
                // The sort keys use this exact day as their "not stated" sentinel, so a stored
                // one would sort among the offers that said nothing.
                Arguments.of("1200", "\"" + ShortlistSort.UNSTATED_DAY + "\""));
    }

    @Test
    void cutsAPhraseRatherThanLettingTheAdvertComeBackAsOne() {
        String wall = "x".repeat(500);
        answers("""
                {"start":{"text":"%s","date":null},
                 "duration":{"text":null,"months":null},
                 "deadline":{"text":null,"date":null}}
                """.formatted(wall));

        assertThat(extractor().extract(OFFER).orElseThrow().startText()).hasSize(FieldExtractor.MAX_PHRASE);
    }

    @Test
    void findsTheObjectInsideAFenceAndInsideAnIntroduction() {
        answers("""
            Here is what the advert says:
            ```json
            {"start":{"text":"ab 01.12.2026","date":"2026-12-01"},
             "duration":{"text":null,"months":null},
             "deadline":{"text":null,"date":null}}
            ```
            """);

        assertThat(extractor().extract(OFFER).orElseThrow().startsOn()).isEqualTo(LocalDate.of(2026, 12, 1));
    }

    @Test
    void tellsAnAnswerApartFromAReplyThatIsNotOne() {
        // The difference the caller needs: one leaves the offer due so the next run finishes
        // it, the other is a finished decision that stands.
        answers("I could not find a start date in this advert.");
        assertThat(extractor().extract(OFFER)).isEmpty();

        MODEL.stubFor(post(anyUrl()).willReturn(aResponse().withStatus(500).withBody("{}")));
        assertThat(extractor().extract(OFFER)).isEmpty();
    }

    private static FieldExtractor extractor() {
        var llm = new PipelineConfig.Llm(
                ChatModels.OPENAI_COMPATIBLE,
                MODEL.baseUrl(),
                "test-key",
                null,
                false,
                new PipelineConfig.Llm.Models(null, "a-model", null, null, null),
                null);
        Optional<org.springframework.ai.chat.model.ChatModel> chatModel = new ChatModels().of(llm, "a-model");
        return new FieldExtractor(chatModel.orElseThrow(), "a-model", new ObjectMapper());
    }

    private static void answers(String content) {
        MODEL.stubFor(post(urlPathEqualTo("/chat/completions"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                        {"id":"chat-1","object":"chat.completion","created":1,"model":"a-model",
                         "choices":[{"index":0,"finish_reason":"stop",
                                     "message":{"role":"assistant","content":%s}}]}
                        """.formatted(
                                        new ObjectMapper().valueToTree(content).toString()))));
    }
}
