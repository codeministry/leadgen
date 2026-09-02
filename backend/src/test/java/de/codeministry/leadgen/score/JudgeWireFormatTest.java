package de.codeministry.leadgen.score;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Two providers, one question. What differs is the shape of the bytes, and every one of
 * those differences fails silently if it is got wrong — a bearer token the server ignores,
 * a system message it rejects, an answer read out of a field that is not there.
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

    @Test
    void speaksTheChatCompletionsFormatWithABearerToken() {
        MODEL.stubFor(post(urlPathEqualTo("/chat/completions"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody(JSON.createObjectNode()
                                .putPOJO(
                                        "choices",
                                        JSON.createArrayNode()
                                                .add(JSON.createObjectNode()
                                                        .putPOJO(
                                                                "message",
                                                                JSON.createObjectNode().put("content", ANSWER))))
                                .toString())));

        var reasons = new OpenAiCompatibleJudge(baseUrl(), "secret", "some-model", JSON, BOUNDS).judge(OFFER);

        assertThat(reasons).extracting(ScoreReason::factor).containsExactly("role_fit", "role_mismatch");
        MODEL.verify(postRequestedFor(urlPathEqualTo("/chat/completions"))
                .withHeader("Authorization", equalTo("Bearer secret"))
                .withRequestBody(matchingJsonPath(
                        "$.messages[?(@.role == 'system')]")));
    }

    @Test
    void speaksTheMessagesFormatWithItsOwnHeadersAndASystemField() {
        // Four differences, each silent: the key header, the version header, the system
        // prompt as a field rather than a message, and `max_tokens`, without which the
        // request is a 400.
        MODEL.stubFor(post(urlPathEqualTo("/v1/messages"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody(JSON.createObjectNode()
                                .putPOJO(
                                        "content",
                                        JSON.createArrayNode()
                                                .add(JSON.createObjectNode()
                                                        .put("type", "thinking")
                                                        .put("thinking", "…"))
                                                .add(JSON.createObjectNode()
                                                        .put("type", "text")
                                                        .put("text", ANSWER)))
                                .toString())));

        var reasons = new AnthropicJudge(baseUrl(), "secret", "some-model", JSON, BOUNDS).judge(OFFER);

        assertThat(reasons).extracting(ScoreReason::factor).containsExactly("role_fit", "role_mismatch");
        MODEL.verify(postRequestedFor(urlPathEqualTo("/v1/messages"))
                .withHeader("x-api-key", equalTo("secret"))
                .withHeader(
                        "anthropic-version", matching("\\d{4}-\\d{2}-\\d{2}"))
                .withRequestBody(matchingJsonPath(
                        "$.system"))
                .withRequestBody(matchingJsonPath(
                        "$.max_tokens")));
    }

    @Test
    void boundsBothAnswersByTheSameWeightTable() {
        // The bounds are what stop a model outvoting the weight table, so they live in one
        // place. A second implementation with its own copy would mean the same offer
        // scores differently depending on who was asked.
        MODEL.stubFor(post(urlPathEqualTo("/v1/messages"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody(JSON.createObjectNode()
                                .putPOJO(
                                        "content",
                                        JSON.createArrayNode()
                                                .add(JSON.createObjectNode()
                                                        .put("type", "text")
                                                        .put("text", ANSWER)))
                                .toString())));

        var reasons = new AnthropicJudge(baseUrl(), "secret", "some-model", JSON, BOUNDS).judge(OFFER);

        assertThat(reasons).extracting(ScoreReason::points).containsExactly(15, -25);
    }

    @Test
    void keepsTheOfferWhenTheProviderAnswersWithSomethingElse() {
        // An answer read out of the wrong field is an empty string, which parses to no
        // reasons — an offer that looks judged and is not. Returning nothing is the same
        // outcome, but it is the one the pipeline is built for.
        MODEL.stubFor(post(urlPathEqualTo("/v1/messages"))
                .willReturn(aResponse().withStatus(400).withBody("{\"error\":\"max_tokens: field required\"}")));

        assertThat(new AnthropicJudge(baseUrl(), "secret", "some-model", JSON, BOUNDS).judge(OFFER))
                .isEmpty();
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

        var batchId = new AnthropicJudge(baseUrl(), "secret", "some-model", JSON, BOUNDS).submit(List.of(OFFER, OTHER));

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

        var outcome = new AnthropicJudge(baseUrl(), "secret", "some-model", JSON, BOUNDS).collect("msgbatch_1");

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
                        .withBody(errored("offer-2") + "\n" + succeeded("offer-1") + "\n")));

        var outcome = new AnthropicJudge(baseUrl(), "secret", "some-model", JSON, BOUNDS).collect("msgbatch_1");

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

        var outcome = new AnthropicJudge(baseUrl(), "secret", "some-model", JSON, BOUNDS).collect("msgbatch_gone");

        assertThat(outcome.status()).isEqualTo(BatchOutcome.Status.FAILED);
        assertThat(outcome.note()).contains("404");
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
