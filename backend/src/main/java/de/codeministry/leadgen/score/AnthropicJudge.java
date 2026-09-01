package de.codeministry.leadgen.score;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.util.List;
import java.util.Map;

/**
 * The Messages API: `POST /v1/messages`, an `x-api-key` header, the system prompt as its
 * own field rather than a message, and the answer in `content[0].text`.
 *
 * <p>Five differences from the OpenAI-compatible format, and every one of them silent if
 * got wrong: a bearer token is simply ignored, a system *message* is rejected as an unknown
 * role, a missing `max_tokens` is a 400, a `temperature` is a 400 on every current model,
 * and reading `choices` out of this response yields an empty string that parses to no
 * reasons at all — an offer that looks judged and is not.
 *
 * <p>Still no vendor in the configuration: `base_url` says where to send it, and this class
 * only says what the bytes look like when it gets there.
 */
public class AnthropicJudge extends HttpJudge {

    /**
     * The answer is a few lines of JSON, and the ceiling is nowhere near that for a reason:
     * on the current models reasoning happens before the text and is counted here too, so a
     * cap sized to the answer truncates the response before the answer begins. A truncated
     * body parses to no reasons, which is the same silent under-scoring as a failed call.
     */
    private static final int MAX_TOKENS = 4096;

    /** Pinned rather than tracked: an unversioned request is refused outright. */
    private static final String API_VERSION = "2023-06-01";

    public AnthropicJudge(String baseUrl, String apiKey, String model, ObjectMapper json) {
        super(baseUrl, apiKey, model, json);
    }

    @Override
    protected HttpRequest request(String instructions, String offer) throws IOException {
        String body = json.writeValueAsString(Map.of(
                "model", model,
                "max_tokens", MAX_TOKENS,
                // No `temperature`. The OpenAI-compatible judge pins it to 0 for a
                // repeatable answer, and this one used to as well — but the current
                // Anthropic models removed the sampling parameters and answer a request
                // carrying one with a 400. A judge that fails returns nothing rather than
                // throwing, so the cost would have been every offer scoring lower with no
                // sign of why.
                // The system prompt is a field here, not a message with role "system".
                "system", instructions,
                "messages", List.of(Map.of("role", "user", "content", offer))));

        return HttpRequest.newBuilder(URI.create(baseUrl + "/v1/messages"))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .header("x-api-key", apiKey)
                .header("anthropic-version", API_VERSION)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    /**
     * The first text block. A response can carry several blocks and the others are not
     * text; picking by index alone would hand the parser a thinking block one day.
     */
    @Override
    protected String contentOf(JsonNode response) {
        for (JsonNode block : response.path("content")) {
            if ("text".equals(block.path("type").asText())) {
                return block.path("text").asText("");
            }
        }
        return "";
    }
}
