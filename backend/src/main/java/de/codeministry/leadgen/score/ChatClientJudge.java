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
import de.codeministry.leadgen.config.model.MatchingRules;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;

/**
 * Everything about judging that is not the wire format.
 *
 * <p>The question, the bounds, the way an offer is described and the way an answer is read
 * back are the same whoever answers. What used to differ between two providers was a URL, an
 * authentication header, the shape of the request body and where the text sits in the
 * response — four things this class no longer knows about, because a {@link ChatModel}
 * knows them instead.
 *
 * <p>Keeping the rest in one place is not tidiness. The bounds are what stop a model
 * outvoting the weight table, and a second implementation that quietly used different ones
 * would mean the same offer scores differently depending on who was asked.
 */
@Slf4j
public class ChatClientJudge implements Judge {

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

    private final ChatModel chatModel;
    protected final String model;
    protected final ObjectMapper json;

    /**
     * What each judged factor is worth, straight from `scoring.weights` and
     * `scoring.penalties`. A factor the table does not name is worth nothing, which is also
     * what happens to a factor the model invents.
     */
    private final Map<String, Integer> bounds;

    public ChatClientJudge(ChatModel chatModel, String model, ObjectMapper json, Map<String, Integer> bounds) {
        this.chatModel = chatModel;
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
            Integer weight =
                    scoring.weights() == null ? null : scoring.weights().get(factor);
            Integer penalty =
                    scoring.penalties() == null ? null : scoring.penalties().get(factor);
            bounds.put(factor, weight != null ? weight : penalty != null ? penalty : 0);
        }
        return Map.copyOf(bounds);
    }

    private int bound(String factor) {
        return bounds.getOrDefault(factor, 0);
    }

    @Override
    public String model() {
        return model;
    }

    /**
     * <b>A judge that fails returns nothing rather than throwing.</b> The offer then keeps
     * its deterministic reasons and scores lower, which is a visible and reviewable
     * outcome; the alternative is one unreachable endpoint ending the whole run.
     *
     * <p>{@code RuntimeException} and not {@code IOException}: the official provider SDKs
     * underneath report a refusal, a rate limit and a severed connection as their own
     * unchecked types, so the checked-exception catch this method used to carry would have
     * seen none of them — every one of the six failure shapes would have ended the run.
     *
     * <p>Deliberately <b>not</b> {@code .entity(...)}. The structured-output converter
     * throws when the answer does not conform, and an exception is precisely the outcome
     * this method exists to avoid; the answer is read the same way a batch entry's is, so
     * both paths keep parsing identically.
     *
     * <p>And deliberately not {@code .content()} either, which returns the <em>first</em>
     * generation. A model with thinking switched on emits the reasoning as a generation of
     * its own, ahead of the answer — so `.content()` hands back the thinking and silently
     * drops the JSON. Measured against a stubbed reply carrying a thinking block: every
     * factor was lost and the only sign was one WARN saying the answer was unusable. This is
     * the same trap the hand-rolled reader documented ("picking by index alone would hand
     * the parser a thinking block one day"), returned through the framework.
     */
    @Override
    public List<ScoreReason> judge(ScoreCandidate offer) {
        try {
            ChatResponse response = ChatClient.create(chatModel)
                    .prompt()
                    .system(instructions())
                    .user(describe(offer))
                    .call()
                    .chatResponse();
            return reasonsOf(textOf(response), offer.id());
        } catch (RuntimeException e) {
            log.warn("Scoring model failed for offer {}: {}", offer.id(), e.getMessage());
            return List.of();
        }
    }

    /**
     * Every generation's text, joined. Which block the object arrived in is not something
     * this has to know — the braces decide, exactly as they do for a fenced or introduced
     * reply, and a provider that answers with one generation is unaffected.
     */
    private static String textOf(ChatResponse response) {
        if (response == null || response.getResults() == null) {
            return "";
        }
        return response.getResults().stream()
                .map(generation -> generation.getOutput() == null
                        ? ""
                        : generation.getOutput().getText())
                .filter(text -> text != null && !text.isBlank())
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    String instructions() {
        return INSTRUCTIONS.formatted(bound(ROLE_FIT), bound(STACK_MISMATCH), bound(ROLE_MISMATCH), bound(VAGUE));
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
     * One answer's text, however it arrived: from a synchronous call or from an entry in a
     * batch's results.
     *
     * <p>Only the four known factors survive, and only inside their bounds. A model that
     * invents a factor or awards itself fifty points is answering a different question, and
     * the weight table is what decides, not the answer. <b>The bounds live here and nowhere
     * else</b> — a second copy for the batch path would mean the same offer scores
     * differently depending on whether the night was busy.
     */
    protected List<ScoreReason> reasonsOf(String content, long offerId) {
        List<ScoreReason> reasons = new ArrayList<>();
        try {
            JsonNode parsed = json.readTree(objectIn(content));

            for (JsonNode node : parsed.path("reasons")) {
                String factor = node.path("factor").asText("");
                if (!JUDGED.contains(factor)) {
                    log.debug(
                            "Offer {}: the model returned factor '{}', which is not one it was asked about",
                            offerId,
                            factor);
                    continue;
                }
                int points = bound(factor, node.path("points").asInt(0));
                String label = node.path("label").asText("");
                if (points != 0 && !label.isBlank()) {
                    reasons.add(new ScoreReason(factor, label, points));
                }
            }
        } catch (IOException e) {
            // The text itself, truncated, because "did not answer with usable JSON" on its
            // own is unactionable: it is the same line whether the model wrote prose, hit
            // its token ceiling mid-object, or answered nothing at all.
            log.warn(
                    "Offer {}: the scoring model did not answer with usable JSON. It said: {}",
                    offerId,
                    abbreviate(content));
            return List.of();
        }
        return reasons;
    }

    /**
     * The JSON object inside whatever the model actually sent.
     *
     * <p>"Answer only with JSON, and nothing else" is an instruction, not a guarantee. Every
     * model that follows it most of the time still wraps the object in a ```json fence or
     * introduces it with a sentence, and both parse to nothing — which lands as four missing
     * factors on an offer that looks judged. Measured against a real endpoint: fenced output
     * cost every offer its role fit and all three penalties, and the only sign was one WARN
     * per offer saying the answer was unusable.
     *
     * <p>Kept rather than delegated to the framework's own cleaner, which strips a fence and
     * nothing else: prose <em>outside</em> a fence is the half that was actually measured
     * here, and the braces catch both.
     *
     * <p>So the braces decide, not the surrounding text. Nothing is repaired here — a
     * genuinely truncated object still fails to parse, and it should.
     */
    static String objectIn(String content) {
        if (content == null) {
            return "";
        }
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        return start >= 0 && end > start ? content.substring(start, end + 1) : content;
    }

    private static String abbreviate(String content) {
        if (content == null || content.isBlank()) {
            return "<nothing>";
        }
        String flattened = content.strip().replaceAll("\\s+", " ");
        return flattened.length() <= 300 ? flattened : flattened.substring(0, 300) + "…";
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
