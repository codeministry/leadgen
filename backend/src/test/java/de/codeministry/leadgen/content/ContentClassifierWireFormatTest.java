/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.content;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import de.codeministry.leadgen.config.model.PipelineConfig;
import de.codeministry.leadgen.llm.ChatModels;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the classifier sends and what it does with what comes back, at the byte level.
 *
 * <p>No Spring context: this is about the wire, and a context would only make it slower to
 * find out that a model answered with prose.
 *
 * <p>The server starts in a <b>static initialiser</b> and not in {@code @BeforeAll}, the same
 * convention {@code JudgeWireFormatTest} follows and for the same reason: anything that has to
 * know the port before the class is set up runs earlier than {@code @BeforeAll} does.
 */
class ContentClassifierWireFormatTest {

    private static final WireMockServer MODEL;

    static {
        MODEL = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        MODEL.start();
    }

    private static final List<ContentClassifier.Candidate> BLOCKS = List.of(
        new ContentClassifier.Candidate(0, "Apply now Save to watchlist"),
        new ContentClassifier.Candidate(1, "Wir suchen eine/n Angular Entwickler."),
        new ContentClassifier.Candidate(2, "Amtsgericht München, HRB 187777"));

    @AfterAll
    static void stop() {
        MODEL.stop();
    }

    @BeforeEach
    void reset() {
        MODEL.resetAll();
    }

    @Test
    void asksAboutTheBlocksByNumberAndNeverAsksForTheAdvertBack() {
        answers("""
            {"blocks":[{"index":0,"kind":"CHROME","reason":"Portal buttons."}]}
            """);

        classifier().classify("Angular Entwickler (m/w/d)", BLOCKS);

        // The advert's title for context, the blocks by index, and nothing that invites the
        // model to rewrite anything. Indices in, indices out is the whole contract.
        MODEL.verify(postRequestedFor(urlPathEqualTo("/chat/completions"))
            .withRequestBody(matching("(?s).*Angular Entwickler.*"))
            .withRequestBody(matching("(?s).*\\[0\\] Apply now Save to watchlist.*"))
            .withRequestBody(matching("(?s).*\\[2\\] Amtsgericht.*")));
    }

    @Test
    void keepsWhatItWasOfferedAndDropsWhatItWasNot() {
        answers(
            """
                {"blocks":[{"index":0,"kind":"CHROME","reason":"Portal buttons."},
                           {"index":2,"kind":"AGENCY","reason":"The recruiter's register entry."},
                           {"index":9,"kind":"CHROME","reason":"A block nobody asked about."},
                           {"index":1,"kind":"NONSENSE","reason":"A kind nobody offered."}]}
                """);

        Map<Integer, ContentClassifier.Labelled> answered =
            classifier().classify("Angular", BLOCKS).orElseThrow();

        assertThat(answered).containsOnlyKeys(0, 2);
        assertThat(answered.get(0).kind()).isEqualTo(ContentKind.CHROME);
        assertThat(answered.get(2).kind()).isEqualTo(ContentKind.AGENCY);
    }

    @Test
    void treatsALabelOfContentAsNoLabelAtAll() {
        // The instruction is to omit a block that belongs to the advert. A model that answers
        // CONTENT means the same thing, and acting on it would write a decision where the
        // honest state is "this is the ad".
        answers("""
            {"blocks":[{"index":1,"kind":"CONTENT","reason":"This is the advert."}]}
            """);

        assertThat(classifier().classify("Angular", BLOCKS).orElseThrow()).isEmpty();
    }

    @Test
    void findsTheObjectInsideAFenceAndInsideAnIntroduction() {
        answers(
            """
                Here is what I found:
                ```json
                {"blocks":[{"index":0,"kind":"FORM","reason":"A dialog."}]}
                ```
                """);

        assertThat(classifier().classify("Angular", BLOCKS).orElseThrow()).containsOnlyKeys(0);
    }

    /**
     * Every one of these leaves the advert whole. That is the property worth pinning: the
     * failure mode of a classifier must be "nothing was hidden", never "half the advert was".
     */
    @ParameterizedTest
    @MethodSource("disappointments")
    void answersNothingRatherThanSomethingWhenTheModelDoesNot(int status, String body) {
        MODEL.stubFor(post(anyUrl())
            .willReturn(aResponse()
                .withStatus(status)
                .withHeader("Content-Type", "application/json")
                .withBody(body)));

        assertThat(classifier().classify("Angular", BLOCKS)).isEmpty();
    }

    static Stream<org.junit.jupiter.params.provider.Arguments> disappointments() {
        return Stream.of(
            org.junit.jupiter.params.provider.Arguments.of(500, "{}"),
            org.junit.jupiter.params.provider.Arguments.of(429, "{}"),
            org.junit.jupiter.params.provider.Arguments.of(401, "{}"));
    }

    @Test
    void answersEmptyWithoutAskingWhenThereIsNothingToAsk() {
        assertThat(classifier().classify("Angular", List.of()).orElseThrow()).isEmpty();
        MODEL.verify(0, postRequestedFor(anyUrl()));
    }

    /**
     * A reply that is not JSON at all is an answer that did not arrive, and it is reported as
     * such rather than as "nothing here is furniture". The caller needs the difference: one
     * leaves the offer due so the next run finishes it, the other is a finished decision that
     * would stand for good.
     */
    @Test
    void tellsAnAnswerApartFromAReplyThatIsNotOne() {
        answers("I am not able to classify these blocks.");
        assertThat(classifier().classify("Angular", BLOCKS)).isEmpty();

        answers("""
            {"blocks":[]}
            """);
        assertThat(classifier().classify("Angular", BLOCKS)).isPresent().get().asInstanceOf(
            org.assertj.core.api.InstanceOfAssertFactories.MAP).isEmpty();
    }

    private static ContentClassifier classifier() {
        var llm = new PipelineConfig.Llm(
            ChatModels.OPENAI_COMPATIBLE,
            MODEL.baseUrl(),
            "test-key",
            false,
            new PipelineConfig.Llm.Models(null, "a-model", null, null, null),
            null);
        Optional<org.springframework.ai.chat.model.ChatModel> chatModel = new ChatModels().of(llm, "a-model");
        return new ContentClassifier(chatModel.orElseThrow(), "a-model", new ObjectMapper());
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
