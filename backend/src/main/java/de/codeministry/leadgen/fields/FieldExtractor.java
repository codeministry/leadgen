/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.fields;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.codeministry.leadgen.llm.Answers;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;

/**
 * Asks a model when an advert starts, how long it runs, and by when it has to be answered.
 *
 * <p>Three facts in, three facts out. <b>The model never writes prose that becomes the
 * advert</b> — every phrase it returns is cut to {@link #MAX_PHRASE} characters, every date
 * has to be ISO and inside {@link #EARLIEST}..{@link #LATEST}, and a month count outside
 * {@link #MIN_MONTHS}..{@link #MAX_MONTHS} is discarded. That is the same shape the scoring
 * judge uses, where the factor names are the contract and the weight table decides what an
 * answer is worth: the bounds live on this side, so a model cannot widen them by answering
 * confidently.
 *
 * <p>One call per offer. The three facts are one question about one advert, and asking them
 * separately would triple the bill for an answer that fits in three lines.
 */
@Slf4j
public class FieldExtractor {

    /**
     * A phrase is a quote from the advert, not a paragraph of it.
     */
    static final int MAX_PHRASE = 200;

    /**
     * No engagement this tool is looking for is shorter than a month or longer than ten years.
     */
    static final int MIN_MONTHS = 1;

    static final int MAX_MONTHS = 120;

    /**
     * The window a real date falls in. It exists mainly to reject the two answers a model
     * gives when it has nothing: an epoch date and a far-future placeholder. The latter
     * matters more than it looks — {@code 9999-12-31} is the sentinel the shortlist's sort
     * keys use for "not stated", so a stored one would sort among the unknowns it is not.
     */
    static final LocalDate EARLIEST = LocalDate.of(2000, 1, 1);

    static final LocalDate LATEST = LocalDate.of(2100, 1, 1);

    private static final String INSTRUCTIONS =
            """
            You are reading a freelance project advert and pulling three facts out of it.

            START     when the engagement begins
            DURATION  how long it runs
            DEADLINE  by when an application has to be in

            For each one, answer with two things:
              "text"  a short quote from the advert, in the advert's own language, saying what
                      it says about that fact. Under 200 characters. Never invent one.
              a value  "date" for START and DEADLINE, "months" for DURATION.

            Rules:
            - If the advert says nothing about a fact, answer null for both halves of it. Most
              adverts state no deadline at all, and that is a normal answer, not a failure.
            - "date" is ISO, YYYY-MM-DD, and only when the advert names a day you can resolve.
              "ab sofort", "Q4/2026" and "ab KW 42" are a text with a null date, not a guess.
            - "months" is the committed minimum, never the optimistic maximum. "6 Monate mit
              Option auf Verlängerung" is 6. "12-18 Monate" is 12.
            - Give a "text" whenever you give a value. A date you cannot quote the advert for
              is a date you inferred, and it will be discarded.
            - The "text" is the value, not the row. Leave the advert's own label out of it:
              "ab 01.10.2026", not "Start: ab 01.10.2026"; "12 Monate", not "Laufzeit: 12
              Monate". The screen puts its own label in front of what you return, so a label
              inside it is printed twice.
            - Some facts may already be stated below the advert, read out of it by a pattern.
              They can be wrong or half-read. Correct them from the advert; your answer is the
              one that is kept.

            Answer only with JSON of this shape, and nothing else:
            {"start":{"text":"ab sofort","date":null},
             "duration":{"text":"6 Monate mit Option auf Verlängerung","months":6},
             "deadline":{"text":null,"date":null}}
            """;

    private final ChatModel chatModel;
    private final String model;
    private final ObjectMapper json;

    public FieldExtractor(ChatModel chatModel, String model, ObjectMapper json) {
        this.chatModel = chatModel;
        this.model = model;
        this.json = json;
    }

    public String model() {
        return model;
    }

    /**
     * The system prompt, for the Rules screen. Nothing is substituted into it: unlike the
     * judge's, this question carries no weights and no profile, because it is about what an
     * advert says and not about who is applying.
     */
    public static String instructions() {
        return INSTRUCTIONS;
    }

    /**
     * The shape of the message an advert arrives in, built by the real builder for the same
     * reason the judge's and the classifier's are: a sample written out by hand drifts from
     * the method it describes, and nothing fails when it does.
     */
    public static String exampleUser() {
        return describe(new Candidate(
                0,
                "<the offer's title>",
                "<the newsletter's short description>",
                "<the fetched advert, with the portal's furniture already removed>",
                LocalDate.of(2026, 10, 1),
                "<whatever the enrichment pattern captured, or nothing>"));
    }

    /**
     * What the advert says, or nothing at all.
     *
     * <p><b>An empty {@link java.util.Optional} means the model did not answer</b>, which is
     * a different thing from answering that the advert states none of the three. The caller
     * needs the difference: one leaves the offer due so it heals on the next run, the other
     * is a finished decision. Nothing is thrown — one unreachable endpoint must not end a
     * run.
     */
    public java.util.Optional<ExtractedFields> extract(Candidate offer) {
        String content = null;
        try {
            ChatResponse response = ChatClient.create(chatModel)
                    .prompt()
                    .system(INSTRUCTIONS)
                    .user(describe(offer))
                    .call()
                    .chatResponse();
            content = Answers.textOf(response);
            return java.util.Optional.of(read(content));
        } catch (RuntimeException e) {
            log.warn("The field extractor failed for offer {}: {}", offer.id(), e.getMessage());
            return java.util.Optional.empty();
        } catch (IOException e) {
            log.warn(
                    "The field extractor did not answer with usable JSON for offer {}. It said: {}",
                    offer.id(),
                    Answers.abbreviate(content));
            return java.util.Optional.empty();
        }
    }

    /**
     * The advert as the extractor sees it, plus what the application already knows about it.
     *
     * <p>The second half is deliberate and is the same argument {@code ChatClientJudge}
     * makes for passing the enriched fields: a model told to find a start date while the row
     * beside it already states one knows less than the application does, and the correction
     * it is being asked for cannot be made against a value it cannot see.
     */
    static String describe(Candidate offer) {
        StringBuilder text = new StringBuilder("Title: ").append(offer.title() == null ? "(untitled)" : offer.title());
        if (offer.description() != null && !offer.description().isBlank()) {
            text.append("\n\nSummary:\n").append(offer.description());
        }
        if (offer.advert() != null && !offer.advert().isBlank()) {
            text.append("\n\nAdvert:\n").append(offer.advert());
        }
        text.append("\n\nAlready read out of it by a pattern (may be wrong or empty):\n");
        text.append("- start: ")
                .append(offer.knownStart() == null ? "nothing" : offer.knownStart())
                .append('\n');
        text.append("- duration: ").append(offer.knownDuration() == null ? "nothing" : offer.knownDuration());
        return text.toString();
    }

    /**
     * The answer, with every value checked against this side's bounds.
     *
     * <p>A value that fails a bound is dropped and the rest is kept. A model that resolves a
     * quarter into a day is wrong about one field, not about the advert.
     */
    private ExtractedFields read(String content) throws IOException {
        JsonNode parsed = json.readTree(Answers.objectIn(content));
        JsonNode start = parsed.path("start");
        JsonNode duration = parsed.path("duration");
        JsonNode deadline = parsed.path("deadline");

        String startText = phrase(start.path("text"));
        String durationText = phrase(duration.path("text"));
        String applyByText = phrase(deadline.path("text"));

        return new ExtractedFields(
                startText,
                startText == null ? null : date(start.path("date")),
                durationText,
                durationText == null ? null : months(duration.path("months")),
                applyByText,
                applyByText == null ? null : date(deadline.path("date")));
    }

    /**
     * A quote, cut to length. Blank, "null" and the absent node are all the same answer.
     */
    private static String phrase(JsonNode node) {
        if (node == null || node.isNull() || !node.isTextual()) {
            return null;
        }
        String text = node.asText().strip();
        if (text.isEmpty() || "null".equalsIgnoreCase(text)) {
            return null;
        }
        return text.length() <= MAX_PHRASE ? text : text.substring(0, MAX_PHRASE);
    }

    private static LocalDate date(JsonNode node) {
        if (node == null || node.isNull() || !node.isTextual()) {
            return null;
        }
        try {
            LocalDate day = LocalDate.parse(node.asText().strip());
            if (day.isBefore(EARLIEST) || day.isAfter(LATEST)) {
                log.debug("The field extractor answered with the date {}, which is outside the window", day);
                return null;
            }
            return day;
        } catch (DateTimeParseException e) {
            log.debug("The field extractor answered with '{}', which is not an ISO date", node.asText());
            return null;
        }
    }

    private static Integer months(JsonNode node) {
        if (node == null || !node.isIntegralNumber()) {
            return null;
        }
        int months = node.asInt();
        if (months < MIN_MONTHS || months > MAX_MONTHS) {
            log.debug("The field extractor answered with {} months, which is outside the bounds", months);
            return null;
        }
        return months;
    }

    /**
     * One offer, as it is offered to the model.
     *
     * @param advert        the advert with the portal's furniture already removed, which is why
     *                      this stage runs after content segmentation
     * @param knownStart    what the enrichment pattern captured, so the model can correct it
     * @param knownDuration the same, for the length of the engagement
     */
    public record Candidate(
            long id, String title, String description, String advert, LocalDate knownStart, String knownDuration) {}
}
