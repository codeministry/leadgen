package de.codeministry.leadgen.score;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.util.List;
import java.util.Map;

/**
 * The OpenAI-compatible chat API: `POST /chat/completions`, a bearer token, and the answer
 * in `choices[0].message.content`.
 *
 * <p>It is the format Ollama, vLLM, LM Studio, OpenRouter and most hosted services speak,
 * which is why it was the first one implemented. No vendor is named here — the base URL
 * decides who answers, and the model comes from `llm.models.scoring`.
 */
public class OpenAiCompatibleJudge extends HttpJudge {

    public OpenAiCompatibleJudge(
            String baseUrl, String apiKey, String model, ObjectMapper json, java.util.Map<String, Integer> bounds) {
        super(baseUrl, apiKey, model, json, bounds);
    }

    @Override
    protected HttpRequest request(String instructions, String offer) throws IOException {
        String body = json.writeValueAsString(Map.of(
                "model", model,
                "temperature", 0,
                "messages",
                        List.of(
                                Map.of("role", "system", "content", instructions),
                                Map.of("role", "user", "content", offer))));

        return HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions"))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    @Override
    protected String contentOf(JsonNode response) {
        return response.path("choices").path(0).path("message").path("content").asText("");
    }
}
