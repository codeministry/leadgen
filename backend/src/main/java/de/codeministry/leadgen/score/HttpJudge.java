package de.codeministry.leadgen.score;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.codeministry.leadgen.config.model.MatchingRules;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

    /**
     * The four factors a model is asked about. The names are the judge's contract, the same
     * way the eight field names are the extractor's — but the numbers behind them are not:
     * they come from the weight table, per run.
     *
     * <p>They used to be four constants here, matching `scoring.weights.role_fit` and the
     * three penalties by coincidence rather than by wiring. Raising `role_fit` in the
     * configuration moved the deterministic half of the score and left the clamp and the
     * prompt text where they were, which is the quiet half of "the weight table decides,
     * not the answer".
     */
    static final String ROLE_FIT = "role_fit";

    static final String STACK_MISMATCH = "stack_mismatch_dominant";
    static final String ROLE_MISMATCH = "role_mismatch";
    static final String VAGUE = "vague_description";

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

    /**
     * Redirects are followed because a batch's results are served from a signed URL the
     * results endpoint redirects to. {@code NORMAL} rather than {@code ALWAYS}: it refuses
     * an HTTPS-to-HTTP downgrade, and the request carries an API key.
     */
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    protected final String baseUrl;
    protected final String apiKey;
    protected final String model;
    protected final ObjectMapper json;

    /**
     * What each judged factor is worth, straight from `scoring.weights` and
     * `scoring.penalties`. A factor the table does not name is worth nothing, which is also
     * what happens to a factor the model invents.
     */
    private final Map<String, Integer> bounds;

    HttpJudge(String baseUrl, String apiKey, String model, ObjectMapper json, Map<String, Integer> bounds) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.json = json;
        this.bounds = bounds;
    }

    /**
     * The bounds for the four judged factors, read out of the configured weight table.
     *
     * <p>Null-tolerant on purpose: a configuration with no scoring block awards nothing, and
     * a judge built against it clamps every factor to zero rather than falling over. The
     * alternative is a judge that cannot be constructed at all, which would take the whole
     * run down over a table nobody filled in.
     */
    static Map<String, Integer> boundsOf(MatchingRules.Scoring scoring) {
        if (scoring == null) {
            return Map.of();
        }
        Map<String, Integer> bounds = new java.util.LinkedHashMap<>();
        for (String factor : List.of(ROLE_FIT, STACK_MISMATCH, ROLE_MISMATCH, VAGUE)) {
            Integer weight = scoring.weights() == null ? null : scoring.weights().get(factor);
            Integer penalty = scoring.penalties() == null ? null : scoring.penalties().get(factor);
            bounds.put(factor, weight != null ? weight : penalty != null ? penalty : 0);
        }
        return Map.copyOf(bounds);
    }

    private int bound(String factor) {
        return bounds.getOrDefault(factor, 0);
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
            HttpResponse<String> response = send(request(instructions(), describe(offer)));

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

    /** The one place a request actually leaves, so a subclass never holds its own client. */
    protected HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException {
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /** 2xx. Anything else is an answer about the request rather than about the offer. */
    protected static boolean ok(HttpResponse<String> response) {
        return response.statusCode() >= 200 && response.statusCode() < 300;
    }

    String instructions() {
        return INSTRUCTIONS.formatted(
                bound(ROLE_FIT), bound(STACK_MISMATCH), bound(ROLE_MISMATCH), bound(VAGUE));
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

    /** The synchronous answer, whose body is one message. */
    private List<ScoreReason> parse(String responseBody, long offerId) {
        try {
            return reasonsOf(json.readTree(responseBody), offerId);
        } catch (IOException e) {
            log.warn("Offer {}: the scoring model did not answer with usable JSON", offerId);
            return List.of();
        }
    }

    /**
     * One message, however it arrived: as the body of a synchronous answer or as an entry
     * in a batch's results.
     *
     * <p>Only the four known factors survive, and only inside their bounds. A model that
     * invents a factor or awards itself fifty points is answering a different question, and
     * the weight table is what decides, not the answer. <b>The bounds live here and nowhere
     * else</b> — a second copy for the batch path would mean the same offer scores
     * differently depending on whether the night was busy.
     */
    protected List<ScoreReason> reasonsOf(JsonNode message, long offerId) {
        List<ScoreReason> reasons = new ArrayList<>();
        try {
            JsonNode parsed = json.readTree(contentOf(message));

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

    /**
     * Clamped to what the factor is worth, in the direction its sign says. A weight bounds a
     * reward at zero below and itself above; a penalty bounds a deduction at itself below
     * and zero above. A factor the table does not name is worth nothing, which is what a
     * model inventing one gets.
     */
    private int bound(String factor, int points) {
        int limit = bound(factor);
        return limit >= 0 ? Math.max(0, Math.min(limit, points)) : Math.max(limit, Math.min(0, points));
    }
}
