package de.codeministry.leadgen.score;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
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

        var reasons = new OpenAiCompatibleJudge(baseUrl(), "secret", "some-model", JSON).judge(OFFER);

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

        var reasons = new AnthropicJudge(baseUrl(), "secret", "some-model", JSON).judge(OFFER);

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

        var reasons = new AnthropicJudge(baseUrl(), "secret", "some-model", JSON).judge(OFFER);

        assertThat(reasons).extracting(ScoreReason::points).containsExactly(15, -25);
    }

    @Test
    void keepsTheOfferWhenTheProviderAnswersWithSomethingElse() {
        // An answer read out of the wrong field is an empty string, which parses to no
        // reasons — an offer that looks judged and is not. Returning nothing is the same
        // outcome, but it is the one the pipeline is built for.
        MODEL.stubFor(post(urlPathEqualTo("/v1/messages"))
                .willReturn(aResponse().withStatus(400).withBody("{\"error\":\"max_tokens: field required\"}")));

        assertThat(new AnthropicJudge(baseUrl(), "secret", "some-model", JSON).judge(OFFER))
                .isEmpty();
    }
}
