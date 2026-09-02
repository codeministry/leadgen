/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.score;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.AnthropicSetup;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.setup.OpenAiSetup;

/**
 * Two providers, one question, asserted at the level of the bytes.
 *
 * <p>The request is built by the provider SDK now rather than by hand, which is the point of
 * the change — but the assertions stay here and stay at the HTTP level, because the bytes are
 * what a provider actually judges and every difference between the two formats fails
 * silently when it is wrong: a bearer token the server ignores, a system message it rejects,
 * an answer read out of a field that is not there.
 *
 * <p>The stubbed responses are complete provider envelopes and not the two fields the reader
 * needs. That is not ceremony: the SDK deserialises the whole object, so a body that is
 * merely enough for us is rejected before the reader ever sees it — which is exactly the
 * class of silent failure this test exists for.
 */
class JudgeWireFormatTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * The weight table this test judges against. Named here rather than taken from the
     * shipped configuration on purpose: this test is about the wire format, and it should
     * fail when the parsing breaks, not when somebody retunes a weight. The clamp itself is
     * covered against the real configuration by `ScoringWithAModelTest`.
     */
    private static final java.util.Map<String, Integer> BOUNDS = java.util.Map.of(
            "role_fit", 15, "stack_mismatch_dominant", -30, "role_mismatch", -25, "vague_description", -10);

    private static final WireMockServer MODEL;

    static {
        MODEL = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        MODEL.start();
    }

    private static final String ANSWER =
            """
            {"reasons":[{"factor":"role_fit","label":"Backend engagement on Spring Boot","points":15},
                        {"factor":"role_mismatch","label":"nope","points":-900},
                        {"factor":"invented","label":"nope","points":50}]}
            """;

    private static final ScoreCandidate OFFER = new ScoreCandidate(
            1L,
            "Senior Java Entwickler Spring Boot (m/w/d)",
            "Ablösung eines Monolithen.",
            null,
            List.of("Java", "Spring Boot"),
            null,
            null,
            null,
            null,
            false);

    private static final ScoreCandidate OTHER = new ScoreCandidate(
            2L, "Scrum Master (m/w/d)", "Kein Code.", null, List.of(), null, null, null, null, false);

    @AfterAll
    static void stop() {
        MODEL.stop();
    }

    @BeforeEach
    void reset() {
        MODEL.resetAll();
    }

    private String baseUrl() {
        return "http://localhost:" + MODEL.port();
    }

    /**
     * The same construction `Judges` performs, kept here rather than reached into: this test
     * is about the wire format, and going through the configuration registry would make it
     * fail when somebody retunes a weight.
     *
     * <p>Zero retries, deliberately. The SDKs retry a 429 and a 5xx by default, and the
     * failure-shape cases below count requests.
     */
    private ChatClientJudge openAiJudge() {
        ChatModel model = OpenAiChatModel.builder()
                .openAiClient(OpenAiSetup.setupSyncClient(
                        baseUrl(),
                        "secret",
                        null,
                        null,
                        null,
                        null,
                        false,
                        false,
                        "some-model",
                        Duration.ofSeconds(5),
                        0,
                        null,
                        null,
                        ObservationRegistry.NOOP,
                        null,
                        List.of()))
                .openAiClientAsync(OpenAiSetup.setupAsyncClient(
                        baseUrl(),
                        "secret",
                        null,
                        null,
                        null,
                        null,
                        false,
                        false,
                        "some-model",
                        Duration.ofSeconds(5),
                        0,
                        null,
                        null,
                        ObservationRegistry.NOOP,
                        null,
                        List.of()))
                .options(OpenAiChatOptions.builder().model("some-model").build())
                .build();
        return new ChatClientJudge(model, "some-model", JSON, BOUNDS);
    }

    private AnthropicJudge anthropicJudge() {
        return anthropicJudge(BOUNDS);
    }

    private AnthropicJudge anthropicJudge(java.util.Map<String, Integer> bounds) {
        ChatModel model = AnthropicChatModel.builder()
                .anthropicClient(
                        AnthropicSetup.setupSyncClient(baseUrl(), "secret", Duration.ofSeconds(5), 0, null, null))
                .anthropicClientAsync(
                        AnthropicSetup.setupAsyncClient(baseUrl(), "secret", Duration.ofSeconds(5), 0, null, null))
                .options(AnthropicChatOptions.builder()
                        .model("some-model")
                        .maxTokens(AnthropicJudge.MAX_TOKENS)
                        .build())
                .build();
        return new AnthropicJudge(model, baseUrl(), "secret", "some-model", JSON, bounds);
    }

    /** A complete Messages-API answer carrying this text. */
    private static ResponseDefinitionBuilder anthropicAnswer(String assistantText) {
        return anthropicAnswer(JSON.createArrayNode()
                .add(JSON.createObjectNode().put("type", "text").put("text", assistantText)));
    }

    private static ResponseDefinitionBuilder anthropicAnswer(com.fasterxml.jackson.databind.node.ArrayNode content) {
        return aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody(JSON.createObjectNode()
                        .put("id", "msg_1")
                        .put("type", "message")
                        .put("role", "assistant")
                        .put("model", "some-model")
                        .putPOJO("content", content)
                        .put("stop_reason", "end_turn")
                        .putNull("stop_sequence")
                        .putPOJO(
                                "usage",
                                JSON.createObjectNode().put("input_tokens", 1).put("output_tokens", 1))
                        .toString());
    }

    /** A complete chat-completions answer carrying this text. */
    private static ResponseDefinitionBuilder openAiAnswer(String assistantText) {
        return aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody(JSON.createObjectNode()
                        .put("id", "chatcmpl-1")
                        .put("object", "chat.completion")
                        .put("created", 1)
                        .put("model", "some-model")
                        .putPOJO(
                                "choices",
                                JSON.createArrayNode()
                                        .add(JSON.createObjectNode()
                                                .put("index", 0)
                                                .putPOJO(
                                                        "message",
                                                        JSON.createObjectNode()
                                                                .put("role", "assistant")
                                                                .put("content", assistantText))
                                                .put("finish_reason", "stop")))
                        .toString());
    }

    @Test
    void speaksTheChatCompletionsFormatWithABearerToken() {
        MODEL.stubFor(post(urlPathEqualTo("/chat/completions")).willReturn(openAiAnswer(ANSWER)));

        var reasons = openAiJudge().judge(OFFER);

        assertThat(reasons).extracting(ScoreReason::factor).containsExactly("role_fit", "role_mismatch");
        MODEL.verify(postRequestedFor(urlPathEqualTo("/chat/completions"))
                .withHeader("Authorization", equalTo("Bearer secret"))
                .withRequestBody(matchingJsonPath("$.messages[?(@.role == 'system')]")));
    }

    @Test
    void speaksTheMessagesFormatWithItsOwnHeadersAndASystemField() {
        // Four differences, each silent: the key header, the version header, the system
        // prompt as a field rather than a message, and `max_tokens`, without which the
        // request is a 400.
        MODEL.stubFor(post(urlPathEqualTo("/v1/messages"))
                .willReturn(anthropicAnswer(JSON.createArrayNode()
                        .add(JSON.createObjectNode()
                                .put("type", "thinking")
                                .put("thinking", "…")
                                .put("signature", "sig"))
                        .add(JSON.createObjectNode().put("type", "text").put("text", ANSWER)))));

        var reasons = anthropicJudge().judge(OFFER);

        assertThat(reasons).extracting(ScoreReason::factor).containsExactly("role_fit", "role_mismatch");
        MODEL.verify(postRequestedFor(urlPathEqualTo("/v1/messages"))
                .withHeader("x-api-key", equalTo("secret"))
                .withHeader("anthropic-version", matching("\\d{4}-\\d{2}-\\d{2}"))
                .withRequestBody(matchingJsonPath("$.system"))
                .withRequestBody(matchingJsonPath("$.max_tokens")));
    }

    @Test
    void boundsBothAnswersByTheSameWeightTable() {
        // The bounds are what stop a model outvoting the weight table, so they live in one
        // place. A second implementation with its own copy would mean the same offer
        // scores differently depending on who was asked.
        MODEL.stubFor(post(urlPathEqualTo("/v1/messages")).willReturn(anthropicAnswer(ANSWER)));

        var reasons = anthropicJudge().judge(OFFER);

        assertThat(reasons).extracting(ScoreReason::points).containsExactly(15, -25);
    }

    @Test
    void keepsTheOfferWhenTheProviderAnswersWithSomethingElse() {
        // An answer read out of the wrong field is an empty string, which parses to no
        // reasons — an offer that looks judged and is not. Returning nothing is the same
        // outcome, but it is the one the pipeline is built for.
        MODEL.stubFor(post(urlPathEqualTo("/v1/messages"))
                .willReturn(aResponse().withStatus(400).withBody("{\"error\":\"max_tokens: field required\"}")));

        assertThat(anthropicJudge().judge(OFFER)).isEmpty();
    }

    @Test
    void readsTheAnswerOutOfAFencedOrIntroducedReply() {
        // "Answer only with JSON" is an instruction, not a guarantee. Measured against a
        // real endpoint: every offer came back fenced, every offer lost its role fit and
        // all three penalties, and the only sign was one WARN per offer. The braces decide.
        MODEL.stubFor(post(urlPathEqualTo("/v1/messages"))
                .willReturn(anthropicAnswer("Here is my assessment:\n```json\n" + ANSWER + "\n```")));

        assertThat(anthropicJudge().judge(OFFER))
                .extracting(ScoreReason::factor)
                .containsExactly("role_fit", "role_mismatch");
    }

    @Test
    void stillFailsOnAnAnswerThatIsNotAnObjectAtAll() {
        // Nothing is repaired: a truncated object or plain prose has to keep failing, or
        // the next silent under-scoring has no signal left at all.
        assertThat(ChatClientJudge.objectIn("I cannot assess this offer.")).isEqualTo("I cannot assess this offer.");
        assertThat(ChatClientJudge.objectIn("")).isEmpty();
        assertThat(ChatClientJudge.objectIn(null)).isEmpty();
    }

    /**
     * Every way an endpoint can disappoint, and the answer is the same one every time.
     *
     * <p>This is the contract the whole scoring stage rests on: <b>a judge that fails returns
     * nothing rather than throwing</b>, so the offer keeps its deterministic reasons and
     * scores lower, which is visible and reviewable. One unreachable endpoint ending a run
     * would be the alternative.
     *
     * <p>It is parameterised rather than written six times because the interesting property
     * is that the list is exhaustive. A new failure shape belongs in {@link #disappointments}
     * and nowhere else.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("disappointments")
    void returnsNothingWhateverTheProviderDoes(String name, ResponseDefinitionBuilder response) {
        MODEL.stubFor(post(urlPathEqualTo("/v1/messages")).willReturn(response));

        assertThat(anthropicJudge().judge(OFFER)).isEmpty();
    }

    private static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> disappointments() {
        return java.util.stream.Stream.of(
                org.junit.jupiter.params.provider.Arguments.of(
                        "server error", aResponse().withStatus(500).withBody("{}")),
                org.junit.jupiter.params.provider.Arguments.of(
                        "rate limited", aResponse().withStatus(429).withBody("{\"error\":\"slow down\"}")),
                org.junit.jupiter.params.provider.Arguments.of(
                        "connection cut",
                        aResponse().withFault(com.github.tomakehurst.wiremock.http.Fault.CONNECTION_RESET_BY_PEER)),
                org.junit.jupiter.params.provider.Arguments.of(
                        "empty body", aResponse().withBody("")),
                org.junit.jupiter.params.provider.Arguments.of(
                        "prose, no JSON at all", anthropicAnswer("I cannot assess this offer.")),
                org.junit.jupiter.params.provider.Arguments.of(
                        "truncated object", anthropicAnswer("{\"reasons\":[{\"fac")));
    }

    @Test
    void readsTheAnswerOutOfAReplyThatWasNeverFencedAtAll() {
        // The fenced case has its own test. This is the other half of the same measurement:
        // a model that introduces its object with a sentence and follows it with another.
        // A Markdown-fence cleaner does not help here, which is why the braces decide.
        MODEL.stubFor(post(urlPathEqualTo("/v1/messages"))
                .willReturn(anthropicAnswer("Sure. " + ANSWER.strip() + " Let me know if you need more.")));

        assertThat(anthropicJudge().judge(OFFER))
                .extracting(ScoreReason::factor)
                .containsExactly("role_fit", "role_mismatch");
    }

    @Test
    void putsTheConfiguredBoundIntoTheQuestionItAsks() {
        // The numbers in the prompt used to be constants that matched the weight table by
        // coincidence. Raising a weight moved the deterministic half of the score and left
        // the question, and the clamp, where they were.
        MODEL.stubFor(post(urlPathEqualTo("/v1/messages")).willReturn(anthropicAnswer(ANSWER)));

        anthropicJudge(java.util.Map.of("role_fit", 7)).judge(OFFER);

        MODEL.verify(postRequestedFor(urlPathEqualTo("/v1/messages"))
                .withRequestBody(matching("(?s).*role_fit\\s+0 to 7.*")));
    }

    // ---- the batch half of the Messages API -------------------------------------------

    @Test
    void handsEveryOfferOverAsOneBatchAddressedByCustomId() {
        // Position is not an address here. The results come back in no particular order and
        // an entry can be missing entirely, so every request has to carry an id that maps
        // back to an offer on its own.
        MODEL.stubFor(post(urlPathEqualTo("/v1/messages/batches"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"msgbatch_1\",\"processing_status\":\"in_progress\"}")));

        var batchId = anthropicJudge().submit(List.of(OFFER, OTHER));

        assertThat(batchId).contains("msgbatch_1");
        MODEL.verify(postRequestedFor(urlPathEqualTo("/v1/messages/batches"))
                .withHeader("x-api-key", equalTo("secret"))
                .withRequestBody(matchingJsonPath("$.requests[?(@.custom_id == 'offer-1')]"))
                .withRequestBody(matchingJsonPath("$.requests[?(@.custom_id == 'offer-2')]"))
                // The batched request is the synchronous one in an envelope. If the two
                // ever diverge, the same offer gets a different question depending on how
                // busy the night was.
                .withRequestBody(matchingJsonPath("$.requests[0].params.system"))
                .withRequestBody(matchingJsonPath("$.requests[0].params.max_tokens")));
    }

    @Test
    void asksAgainWhileTheBatchHasNotEnded() {
        MODEL.stubFor(get(urlPathEqualTo("/v1/messages/batches/msgbatch_1"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"msgbatch_1\",\"processing_status\":\"in_progress\"}")));

        var outcome = anthropicJudge().collect("msgbatch_1");

        assertThat(outcome.status()).isEqualTo(BatchOutcome.Status.PENDING);
        assertThat(outcome.reasons()).isEmpty();
    }

    @Test
    void readsTheResultsByCustomIdAndBoundsThemLikeASynchronousAnswer() {
        MODEL.stubFor(get(urlPathEqualTo("/v1/messages/batches/msgbatch_1"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"msgbatch_1\",\"processing_status\":\"ended\"}")));
        // Deliberately reversed, and with one entry that did not succeed: neither may shift
        // an answer onto the wrong offer, and the failed one must not become an empty
        // answer — the offer has to stay unjudged so the next run picks it up.
        MODEL.stubFor(get(urlPathEqualTo("/v1/messages/batches/msgbatch_1/results"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/x-jsonl")
                        .withBody(errored("offer-2") + "\n" + unaddressed() + "\n" + succeeded("offer-1") + "\n")));

        var outcome = anthropicJudge().collect("msgbatch_1");

        assertThat(outcome.status()).isEqualTo(BatchOutcome.Status.ENDED);
        assertThat(outcome.reasons()).containsOnlyKeys(1L);
        assertThat(outcome.reasons().get(1L))
                .extracting(ScoreReason::factor)
                .containsExactly("role_fit", "role_mismatch");
        // The same clamping as the synchronous path: -900 becomes -25, and the invented
        // factor is dropped.
        assertThat(outcome.reasons().get(1L)).extracting(ScoreReason::points).containsExactly(15, -25);
    }

    @Test
    void givesUpOnABatchTheProviderWillNotTalkAbout() {
        // Pending would hold its offers forever, and nothing would say so.
        MODEL.stubFor(get(urlPathEqualTo("/v1/messages/batches/msgbatch_gone"))
                .willReturn(aResponse().withStatus(404)));

        var outcome = anthropicJudge().collect("msgbatch_gone");

        assertThat(outcome.status()).isEqualTo(BatchOutcome.Status.FAILED);
        assertThat(outcome.note()).contains("404");
    }

    /** A line addressed to something that is not one of our offers. It has to be skipped
     * rather than parsed: the id is the only address here, and a batch that came back with
     * somebody else's entry in it must not put an answer on one of ours. */
    private static String unaddressed() {
        return JSON.createObjectNode()
                .put("custom_id", "not-ours-at-all")
                .putPOJO("result", JSON.createObjectNode().put("type", "succeeded"))
                .toString();
    }

    private static String succeeded(String customId) {
        return JSON.createObjectNode()
                .put("custom_id", customId)
                .putPOJO(
                        "result",
                        JSON.createObjectNode()
                                .put("type", "succeeded")
                                .putPOJO(
                                        "message",
                                        JSON.createObjectNode()
                                                .putPOJO(
                                                        "content",
                                                        JSON.createArrayNode()
                                                                .add(JSON.createObjectNode()
                                                                        .put("type", "text")
                                                                        .put("text", ANSWER)))))
                .toString();
    }

    private static String errored(String customId) {
        return JSON.createObjectNode()
                .put("custom_id", customId)
                .putPOJO("result", JSON.createObjectNode().put("type", "errored"))
                .toString();
    }
}
