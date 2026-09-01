package de.codeministry.leadgen.score;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

/**
 * Everything about judging that is not the wire format.
 *
 * <p>The question, the bounds, the way an offer is described and the way an answer is read
 * back are the same whoever answers. What differs between two providers is one URL, one
 * authentication header, the shape of the request body and where the text sits in the
 * response — four things, and they are the only four a subclass owns.
 *
 * <p>Keeping them in one place is not tidiness. The bounds are what stop a model
 * outvoting the weight table, and a second implementation that quietly used different ones
 * would mean the same offer scores differently depending on who was asked.
 */
@Slf4j
abstract class HttpJudge implements Judge {

    static final Duration TIMEOUT = Duration.ofSeconds(30);

    /** Bounded by the weight table, so the model cannot award itself more than it is worth. */
    private static final int ROLE_FIT = 15;

    private static final int STACK_MISMATCH = -30;
    private static final int ROLE_MISMATCH = -25;
    private static final int VAGUE = -10;

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
    protected final String baseUrl;
    protected final String apiKey;
    protected final String model;
    protected final ObjectMapper json;

    HttpJudge(String baseUrl, String apiKey, String model, ObjectMapper json) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.json = json;
    }

    /** The request this provider expects, system prompt and user message already built. */
    protected abstract HttpRequest request(String instructions, String offer) throws IOException;

    /** Where this provider puts the assistant's text. */
    protected abstract String contentOf(JsonNode response);

    @Override
    public String model() {
        return model;
    }

    /**
     * <b>A judge that fails returns nothing rather than throwing.</b> The offer then keeps
     * its deterministic reasons and scores lower, which is a visible and reviewable
     * outcome; the alternative is one unreachable endpoint ending the whole run.
     */
    @Override
    public List<ScoreReason> judge(ScoreCandidate offer) {
        try {
            HttpResponse<String> response =
                    http.send(request(instructions(), describe(offer)), HttpResponse.BodyHandlers.ofString());

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

    static String instructions() {
        return INSTRUCTIONS.formatted(ROLE_FIT, STACK_MISMATCH, ROLE_MISMATCH, VAGUE);
    }

    static String describe(ScoreCandidate offer) {
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
            JsonNode parsed = json.readTree(contentOf(json.readTree(responseBody)));

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
            case "role_fit" -> Math.max(0, Math.min(ROLE_FIT, points));
            case "stack_mismatch_dominant" -> Math.max(STACK_MISMATCH, Math.min(0, points));
            case "role_mismatch" -> Math.max(ROLE_MISMATCH, Math.min(0, points));
            case "vague_description" -> Math.max(VAGUE, Math.min(0, points));
            default -> 0;
        };
    }
}
