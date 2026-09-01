package de.codeministry.leadgen.score;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 * Asks a model the four questions rules cannot answer: does the role fit, is the stack
 * dominated by something foreign, is the role the wrong one, and is the description too
 * vague to judge.
 *
 * <p>The wire format is the OpenAI-compatible chat API, which Ollama and most hosted
 * services speak; the base URL decides who answers. No vendor is named here, and the model
 * comes from `llm.models.scoring`.
 *
 * <p><b>A judge that fails returns nothing rather than throwing.</b> The offer then keeps
 * its deterministic reasons and scores lower, which is a visible and reviewable outcome —
 * the alternative is one unreachable endpoint ending the whole run.
 *
 * <p>The model may only return points inside the weights it was given, and every reason it
 * returns is checked against the factor list before it is kept. A model that invents a
 * factor is answering a different question, and its answer is dropped rather than scored.
 */
@Slf4j
public class LlmJudge implements Judge {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private static final String INSTRUCTIONS =
            """
            You assess freelance project offers for a senior Java, Spring Boot and Angular
            developer who works from Germany.

            Answer only with JSON of this shape, and nothing else:
            {"reasons":[{"factor":"role_fit","label":"...","points":0}]}

            Use only these factors, each at most once:
              role_fit                  0 to %d, how well the described role matches a
                                        backend, fullstack or architecture engagement
              stack_mismatch_dominant   %d to 0, if the dominant stack is something else
              role_mismatch             %d to 0, if the role is QA, PO, scrum master or support
              vague_description         %d to 0, if the text says too little to judge

            Omit a factor entirely rather than scoring it zero. Every label must name
            something the offer actually says, in one short sentence, in English.
            """;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final ObjectMapper json;

    public LlmJudge(String baseUrl, String apiKey, String model, ObjectMapper json) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.json = json;
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    public List<ScoreReason> judge(ScoreCandidate offer) {
        try {
            String body = json.writeValueAsString(Map.of(
                    "model", model,
                    "temperature", 0,
                    "messages",
                            List.of(
                                    Map.of("role", "system", "content", instructions()),
                                    Map.of("role", "user", "content", describe(offer)))));

            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions"))
                            .timeout(TIMEOUT)
                            .header("Content-Type", "application/json")
                            .header("Authorization", "Bearer " + apiKey)
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("Scoring model answered {} for offer {}; it keeps its deterministic reasons",
                        response.statusCode(), offer.id());
                return List.of();
            }
            return parse(response.body(), offer.id());
        } catch (IOException e) {
            log.warn("Scoring model unreachable for offer {}: {}", offer.id(), e.getMessage());
            return List.of();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        }
    }

    private static String instructions() {
        // The bounds come from the weight table so the model cannot outvote it.
        return INSTRUCTIONS.formatted(15, -30, -25, -10);
    }

    private static String describe(ScoreCandidate offer) {
        StringBuilder text = new StringBuilder();
        text.append("Title: ").append(offer.title()).append('\n');
        if (offer.tags() != null && !offer.tags().isEmpty()) {
            text.append("Tags: ").append(String.join(", ", offer.tags())).append('\n');
        }
        text.append("Description: ").append(offer.description()).append('\n');
        if (offer.fullText() != null && !offer.fullText().isBlank()) {
            text.append("Original ad: ").append(offer.fullText()).append('\n');
        }
        return text.toString();
    }

    /**
     * Only the four known factors survive, and only inside their bounds. A model that
     * invents a factor or awards itself fifty points is answering a different question,
     * and the weight table is what decides, not the answer.
     */
    private List<ScoreReason> parse(String responseBody, long offerId) {
        List<ScoreReason> reasons = new ArrayList<>();
        try {
            JsonNode content = json.readTree(responseBody)
                    .path("choices")
                    .path(0)
                    .path("message")
                    .path("content");
            JsonNode parsed = json.readTree(content.asText(""));

            for (JsonNode node : parsed.path("reasons")) {
                String factor = node.path("factor").asText("");
                if (!JUDGED.contains(factor)) {
                    log.debug("Offer {}: the model returned factor '{}', which is not one it was asked about",
                            offerId, factor);
                    continue;
                }
                int points = bound(factor, node.path("points").asInt(0));
                String label = node.path("label").asText("");
                if (points != 0 && !label.isBlank()) {
                    reasons.add(new ScoreReason(factor, label, points));
                }
            }
        } catch (IOException e) {
            log.warn("Offer {}: the scoring model did not answer with usable JSON", offerId);
            return List.of();
        }
        return reasons;
    }

    private static int bound(String factor, int points) {
        return switch (factor) {
            case "role_fit" -> Math.max(0, Math.min(15, points));
            case "stack_mismatch_dominant" -> Math.max(-30, Math.min(0, points));
            case "role_mismatch" -> Math.max(-25, Math.min(0, points));
            case "vague_description" -> Math.max(-10, Math.min(0, points));
            default -> 0;
        };
    }
}
