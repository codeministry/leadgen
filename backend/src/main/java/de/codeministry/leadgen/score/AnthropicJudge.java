/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.score;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;

/**
 * The one provider whose <em>batch</em> endpoint is implemented, which is the only reason
 * this class still exists.
 *
 * <p>The synchronous half is gone from here entirely: it is {@link ChatClientJudge}'s, and
 * the five differences between the two wire formats that used to be spelled out in this
 * javadoc — the key header, the version header, the system prompt as a field rather than a
 * message, the mandatory `max_tokens`, the answer in `content[]` and not `choices[]` — are
 * now the provider SDK's problem rather than ours. Every one of them failed silently when
 * got wrong.
 *
 * <p><b>The batch half is still hand-rolled HTTP, deliberately.</b> Spring AI 2.0 has no
 * batch abstraction; the official SDK underneath does, but only behind its beta surface
 * (`client.beta().messages().batches()`), and taking it would mean rebuilding the request
 * as typed `MessageCreateParams`, re-reading the results as typed objects, and rewriting
 * the four tests that pin the JSONL contract — to arrive at the same two endpoints this
 * already calls correctly. The trade was measured and declined: this is the least risky
 * code in the package, it is pinned by tests, and it is an opt-in path most runs never
 * take. Revisit when the batch API leaves beta.
 *
 * <p>Still no vendor in the configuration: `base_url` says where to send it, and this class
 * only says what the bytes look like when it gets there.
 */
@Slf4j
public class AnthropicJudge extends ChatClientJudge implements BatchJudge {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    /**
     * The answer is a few lines of JSON, and the ceiling is nowhere near that for a reason:
     * on the current models reasoning happens before the text and is counted here too, so a
     * cap sized to the answer truncates the response before the answer begins. A truncated
     * body parses to no reasons, which is the same silent under-scoring as a failed call.
     */
    static final int MAX_TOKENS = 4096;

    /** Pinned rather than tracked: an unversioned request is refused outright. */
    private static final String API_VERSION = "2023-06-01";

    /**
     * A batch entry is addressed by this and by nothing else, so it has to survive the round
     * trip and come back parseable. The provider caps it at 64 characters and requires it to
     * be unique within the batch, which an offer id is.
     */
    private static final String CUSTOM_ID_PREFIX = "offer-";

    /**
     * Redirects are followed because a batch's results are served from a signed URL the
     * results endpoint redirects to. {@code NORMAL} rather than {@code ALWAYS}: it refuses
     * an HTTPS-to-HTTP downgrade, and the request carries an API key.
     */
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final String baseUrl;
    private final String apiKey;

    public AnthropicJudge(
            ChatModel chatModel,
            String baseUrl,
            String apiKey,
            String model,
            ObjectMapper json,
            Map<String, Integer> bounds) {
        super(chatModel, model, json, bounds);
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
    }

    /**
     * One batched request's body.
     *
     * <p>No `temperature`. The current models removed the sampling parameters and answer a
     * request carrying one with a 400. A judge that fails returns nothing rather than
     * throwing, so the cost would have been every offer scoring lower with no sign of why.
     * The system prompt is a field here, not a message with role "system".
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
                            "custom_id",
                            CUSTOM_ID_PREFIX + offer.id(),
                            "params",
                            message(instructions(), describe(offer))))
                    .toList();

            HttpResponse<String> response =
                    send(post("/v1/messages/batches", json.writeValueAsString(Map.of("requests", requests))));
            if (!ok(response)) {
                log.warn(
                        "The provider answered {} to a batch of {} offers; they stay due",
                        response.statusCode(),
                        offers.size());
                return Optional.empty();
            }
            String id = json.readTree(response.body()).path("id").asText("");
            if (id.isBlank()) {
                log.warn(
                        "The provider accepted a batch of {} offers without naming it; nothing can be collected",
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
                log.warn(
                        "A batch result is addressed to '{}', which is not an offer of ours",
                        entry.path("custom_id").asText());
                continue;
            }
            JsonNode result = entry.path("result");
            String type = result.path("type").asText("");
            if (!"succeeded".equals(type)) {
                log.warn("Offer {}: the batch entry came back '{}'; it keeps its deterministic reasons", offerId, type);
                continue;
            }
            // Through the same reader the synchronous path uses, bounds and all: a second
            // copy would mean the same offer scores differently depending on whether the
            // night was busy.
            reasons.put(offerId, reasonsOf(contentOf(result.path("message")), offerId));
        }
        return reasons;
    }

    /**
     * The first text block of a batched answer. A response can carry several blocks and the
     * others are not text; picking by index alone would hand the parser a thinking block one
     * day.
     */
    private static String contentOf(JsonNode message) {
        for (JsonNode block : message.path("content")) {
            if ("text".equals(block.path("type").asText())) {
                return block.path("text").asText("");
            }
        }
        return "";
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

    private HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException {
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /** 2xx. Anything else is an answer about the request rather than about the offer. */
    private static boolean ok(HttpResponse<String> response) {
        return response.statusCode() >= 200 && response.statusCode() < 300;
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
        return builder.timeout(TIMEOUT).header("x-api-key", apiKey).header("anthropic-version", API_VERSION);
    }
}
