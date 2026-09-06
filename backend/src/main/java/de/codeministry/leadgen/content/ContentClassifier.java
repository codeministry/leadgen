/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.content;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.codeministry.leadgen.llm.Answers;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Asks a model what the blocks nobody recognised are.
 *
 * <p>Indices in, indices out. <b>The model never rewrites the advert and never returns prose
 * that becomes the advert</b> — it answers with numbers and a label from a closed list, and
 * anything else it says is dropped. That is what keeps a classifier from quietly becoming a
 * summariser, and it is the same shape the scoring judge uses, where the factor names are the
 * contract and the weight table decides what they are worth.
 *
 * <p>One call per offer, never one per block: the blocks of one advert are the context that
 * makes a signature recognisable as a signature.
 */
@Slf4j
public class ContentClassifier {

    private static final String INSTRUCTIONS =
        """
            You are cleaning up job adverts that were scraped from freelance portals. The scrape
            keeps the advert, and it also keeps whatever the page had around it.

            You will get an advert's title and a numbered list of text blocks. Say, for each block
            that is NOT part of the advert, what it is instead.

            Use only these labels:
              CHROME    portal furniture: navigation, meta rows, buttons like "Apply now",
                        "Save to watchlist", "Print", "Report"
              FORM      a dialog or a form and its labels, including its explanatory sentences
              TAXONOMY  the portal's own list of skill or category tags, which is not what the
                        advert asked for. Usually a long run of technology names with no sentence
              AGENCY    the recruiter's standing signature: postal address, company register,
                        managing directors, links to a privacy policy or to their other listings
              LEGAL     disclaimers, privacy notices, equal-opportunity boilerplate

            Rules:
            - Omit a block entirely if it is part of the advert. Do not label it.
            - A block that describes the work, the requirements, the stack, the client, the rate,
              the duration or the location is part of the advert. So is the greeting and the name
              of the person offering it.
            - When you are not sure, omit the block. Leaving something in costs a reader a few
              seconds; taking something out costs them the project.
            - The reason is one short sentence, in English, naming what the block actually is.

            Answer only with JSON of this shape, and nothing else:
            {"blocks":[{"index":3,"kind":"TAXONOMY","reason":"A list of portal skill tags."}]}
            """;

    private final ChatModel chatModel;
    private final String model;
    private final ObjectMapper json;

    public ContentClassifier(ChatModel chatModel, String model, ObjectMapper json) {
        this.chatModel = chatModel;
        this.model = model;
        this.json = json;
    }

    public String model() {
        return model;
    }

    /**
     * The system prompt, for the Rules screen. Nothing is substituted into it — unlike the
     * judge's, this one carries no weights and no profile, because the question is about a
     * paragraph and not about who is applying.
     */
    public static String instructions() {
        return INSTRUCTIONS;
    }

    /**
     * The shape of the message an advert arrives in, built by the real builder for the same
     * reason the judge's is: a sample written out by hand drifts from the method it describes,
     * and nothing fails when it does.
     */
    public static String exampleUser() {
        return describe(
                "<the offer's title>",
                List.of(
                        new Candidate(0, "<the first block of the advert, up to 200 characters>"),
                        new Candidate(1, "<the second block>")));
    }

    /**
     * What the given blocks are, keyed by their index.
     *
     * <p><b>An empty {@link Optional} means the model did not answer</b>, which is a different
     * thing from answering that everything is part of the advert. The caller needs the
     * difference: one leaves the offer due so it heals on the next run, the other is a
     * finished decision. Nothing is thrown — one unreachable endpoint must not end a run.
     */
    public Optional<Map<Integer, Labelled>> classify(String title, List<Candidate> blocks) {
        if (blocks.isEmpty()) {
            return Optional.of(Map.of());
        }
        String content = null;
        try {
            ChatResponse response = ChatClient.create(chatModel)
                .prompt()
                .system(INSTRUCTIONS)
                .user(describe(title, blocks))
                .call()
                .chatResponse();
            content = Answers.textOf(response);
            return Optional.of(read(content, blocks));
        } catch (RuntimeException e) {
            log.warn("The content classifier failed: {}", e.getMessage());
            return Optional.empty();
        } catch (IOException e) {
            log.warn("The content classifier did not answer with usable JSON. It said: {}", Answers.abbreviate(content));
            return Optional.empty();
        }
    }

    /**
     * The advert as the classifier sees it: a title for context, then the blocks by number.
     * Only the head of each block, because recognising a report dialog does not need its
     * fourth radio label — and the whole ad twice is the cost this stage exists to avoid.
     */
    static String describe(String title, List<Candidate> blocks) {
        StringBuilder text = new StringBuilder("Advert: ").append(title == null ? "(untitled)" : title);
        text.append("\n\nBlocks:\n");
        for (Candidate block : blocks) {
            text.append('[').append(block.index()).append("] ").append(block.sample()).append('\n');
        }
        return text.toString();
    }

    /**
     * Only indices that were actually asked about, and only kinds the enum knows. A model that
     * invents either is answering about something that is not on the screen, and acting on it
     * would hide a block nobody can point at.
     */
    private Map<Integer, Labelled> read(String content, List<Candidate> asked) throws IOException {
        var indices = asked.stream().map(Candidate::index).collect(Collectors.toSet());
        Map<Integer, Labelled> labels = new HashMap<>();
        JsonNode parsed = json.readTree(Answers.objectIn(content));
        for (JsonNode node : parsed.path("blocks")) {
            int index = node.path("index").asInt(-1);
            if (!indices.contains(index)) {
                log.debug("The classifier answered about block {}, which it was not asked about", index);
                continue;
            }
            ContentKind kind = kindOf(node.path("kind").asText(""));
            // CONTENT is not one of the answers: the instruction is to omit a block that
            // belongs to the advert, and a model labelling one CONTENT means the same thing.
            if (kind == null || kind.isContent()) {
                continue;
            }
            String reason = node.path("reason").asText("");
            labels.put(index, new Labelled(kind, reason.isBlank() ? null : reason));
        }
        return labels;
    }

    private static ContentKind kindOf(String answered) {
        return Arrays.stream(ContentKind.values())
            .filter(kind -> kind.name().equalsIgnoreCase(answered.trim()))
            .findFirst()
            .orElseGet(() -> {
                log.debug("The classifier returned kind '{}', which is not one it was offered", answered);
                return null;
            });
    }

    /**
     * One block, as it is offered to the model.
     */
    public record Candidate(int index, String sample) {
    }

    /**
     * One block, as the model answered about it.
     */
    public record Labelled(ContentKind kind, String reason) {
    }
}
