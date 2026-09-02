package de.codeministry.leadgen.score;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;

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
 * <p>This is also the one provider whose batch endpoint is implemented, which is why it is
 * the one {@link BatchJudge}. The batched and the synchronous request carry the identical
 * body; only the envelope differs, so there is no second way to ask the question.
 *
 * <p>Still no vendor in the configuration: `base_url` says where to send it, and this class
 * only says what the bytes look like when it gets there.
 */
@Slf4j
public class AnthropicJudge extends HttpJudge implements BatchJudge {

    /**
     * The answer is a few lines of JSON, and the ceiling is nowhere near that for a reason:
     * on the current models reasoning happens before the text and is counted here too, so a
     * cap sized to the answer truncates the response before the answer begins. A truncated
     * body parses to no reasons, which is the same silent under-scoring as a failed call.
     */
    private static final int MAX_TOKENS = 4096;

    /** Pinned rather than tracked: an unversioned request is refused outright. */
    private static final String API_VERSION = "2023-06-01";

    /**
     * A batch entry is addressed by this and by nothing else, so it has to survive the round
     * trip and come back parseable. The provider caps it at 64 characters and requires it to
     * be unique within the batch, which an offer id is.
     */
    private static final String CUSTOM_ID_PREFIX = "offer-";

    public AnthropicJudge(
            String baseUrl, String apiKey, String model, ObjectMapper json, java.util.Map<String, Integer> bounds) {
        super(baseUrl, apiKey, model, json, bounds);
    }

    @Override
    protected HttpRequest request(String instructions, String offer) throws IOException {
        return post("/v1/messages", json.writeValueAsString(message(instructions, offer)));
    }

    /**
     * One request's body, shared by both paths.
     *
     * <p>No `temperature`. The OpenAI-compatible judge pins it to 0 for a repeatable
     * answer, and this one used to as well — but the current Anthropic models removed the
     * sampling parameters and answer a request carrying one with a 400. A judge that fails
     * returns nothing rather than throwing, so the cost would have been every offer scoring
     * lower with no sign of why. The system prompt is a field here, not a message with role
     * "system".
     */
    private Map<String, Object> message(String instructions, String offer) {
        return Map.of(
                "model", model,
                "max_tokens", MAX_TOKENS,
                "system", instructions,
                "messages", List.of(Map.of("role", "user", "content", offer)));
    }

    @Override
    public Optional<String> submit(List<ScoreCandidate> offers) {
        if (offers.isEmpty()) {
            return Optional.empty();
        }
        try {
            List<Map<String, Object>> requests = offers.stream()
                    .map(offer -> Map.<String, Object>of(
                            "custom_id", CUSTOM_ID_PREFIX + offer.id(),
                            "params", message(instructions(), describe(offer))))
                    .toList();

            HttpResponse<String> response = send(
                    post("/v1/messages/batches", json.writeValueAsString(Map.of("requests", requests))));
            if (!ok(response)) {
                log.warn("The provider answered {} to a batch of {} offers; they stay due", response.statusCode(),
                        offers.size());
                return Optional.empty();
            }
            String id = json.readTree(response.body()).path("id").asText("");
            if (id.isBlank()) {
                log.warn("The provider accepted a batch of {} offers without naming it; nothing can be collected",
                        offers.size());
                return Optional.empty();
            }
            return Optional.of(id);
        } catch (IOException e) {
            log.warn("A batch of {} offers could not be submitted: {}", offers.size(), e.getMessage());
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    @Override
    public BatchOutcome collect(String batchId) {
        try {
            HttpResponse<String> head = send(get(baseUrl + "/v1/messages/batches/" + batchId));
            if (!ok(head)) {
                // A batch the provider will not talk about is not going to arrive. Failing
                // it releases the offers; leaving it pending would hold them forever.
                return BatchOutcome.failed(
                        "the provider answered " + head.statusCode() + " when asked about this batch");
            }
            String status = json.readTree(head.body()).path("processing_status").asText("");
            if (!"ended".equals(status)) {
                return BatchOutcome.pending();
            }

            HttpResponse<String> results = send(get(baseUrl + "/v1/messages/batches/" + batchId + "/results"));
            if (!ok(results)) {
                return BatchOutcome.failed("the batch ended but its results answered " + results.statusCode());
            }
            return BatchOutcome.ended(read(results.body()));
        } catch (IOException e) {
            // Unreachable is a fact about the moment, not about the batch. Pending, so the
            // next poll asks again rather than discarding answers already paid for.
            log.warn("Batch {} could not be reached: {}", batchId, e.getMessage());
            return BatchOutcome.pending();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return BatchOutcome.pending();
        }
    }

    /**
     * The results are JSONL, one line per request, in no particular order — which is why
     * every line is addressed by its `custom_id` and never by position.
     *
     * <p>An entry that did not succeed contributes nothing and is logged. It is not an
     * error: the offer keeps its deterministic reasons, exactly as it would after a failed
     * synchronous call, and the collector releases it either way.
     */
    private Map<Long, List<ScoreReason>> read(String jsonl) throws IOException {
        Map<Long, List<ScoreReason>> reasons = new LinkedHashMap<>();
        for (String line : jsonl.split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            JsonNode entry = json.readTree(line);
            Long offerId = offerId(entry.path("custom_id").asText(""));
            if (offerId == null) {
                log.warn("A batch result is addressed to '{}', which is not an offer of ours",
                        entry.path("custom_id").asText());
                continue;
            }
            JsonNode result = entry.path("result");
            String type = result.path("type").asText("");
            if (!"succeeded".equals(type)) {
                log.warn("Offer {}: the batch entry came back '{}'; it keeps its deterministic reasons",
                        offerId, type);
                continue;
            }
            reasons.put(offerId, reasonsOf(result.path("message"), offerId));
        }
        return reasons;
    }

    /** Null rather than an exception: one unrecognisable line must not discard the rest. */
    private static Long offerId(String customId) {
        if (!customId.startsWith(CUSTOM_ID_PREFIX)) {
            return null;
        }
        try {
            return Long.valueOf(customId.substring(CUSTOM_ID_PREFIX.length()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private HttpRequest post(String path, String body) {
        return authenticated(HttpRequest.newBuilder(URI.create(baseUrl + path)))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    private HttpRequest get(String url) {
        return authenticated(HttpRequest.newBuilder(URI.create(url))).GET().build();
    }

    private HttpRequest.Builder authenticated(HttpRequest.Builder builder) {
        return builder.timeout(TIMEOUT)
                .header("x-api-key", apiKey)
                .header("anthropic-version", API_VERSION);
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
