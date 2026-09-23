/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.ask;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.codeministry.leadgen.filter.TextFold;
import de.codeministry.leadgen.llm.Answers;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;

/**
 * Asks one advert one bounded question, and refuses to pass on an answer it cannot check.
 *
 * <h2>Why this needs no retrieval</h2>
 *
 * <p>One de-furnitured advert is a few thousand characters and fits whole in any context window
 * worth configuring. Retrieval exists to choose what to put in a prompt when everything will
 * not fit; here everything fits, so choosing would only be a way to leave something out. This
 * is the carve-out {@code docs/decisions/retrieval.md} names when it refuses corpus-wide
 * question answering: the right question at the right granularity.
 *
 * <h2>The quote is the whole design</h2>
 *
 * <p>The model answers with a sentence <b>and the sentence from the advert it rests on</b>, and
 * a quote that is not in the advert means the claim is dropped. That is the same rule the
 * measurement scripts follow — a table of similarities nobody can check against the adverts
 * behind them is a table nobody should act on — applied to prose instead of numbers. Without
 * it this screen would be a confident paragraph about a document the reader is looking at,
 * which is the most expensive kind of wrong: plausible, specific, and unfalsifiable at a
 * glance.
 *
 * <p>The check folds both sides ({@link TextFold}) before comparing, so a model that
 * normalises whitespace, a dash or a quotation mark still passes. What it cannot pass is
 * inventing a sentence.
 */
@Slf4j
class AdvertAsker {

    /**
     * Bounded on purpose, and the bounds are in the instruction rather than in the parsing:
     * the answer is short, in the advert's language, and it is about this advert only.
     *
     * <p>It says what to do when the advert is silent, because that is the answer that
     * matters most and the one a model will otherwise fill in from what adverts usually say.
     */
    private static final String INSTRUCTIONS = """
            You are reading one job advert and answering one question about it.

            Answer only from the advert's own text. Do not use what such adverts usually say,
            what the client is likely to want, or anything you know about the company.

            If the advert does not address the question, say so. That is the expected answer
            for most questions on most adverts and it is never a failure.

            Answer with this JSON and nothing else:
            {"stated": true, "answer": "one or two sentences", "quote": "the exact sentence from the advert"}
            {"stated": false}

            `answer` is in the language the advert is written in.
            `quote` is copied from the advert character for character. Never paraphrase it,
            never join two sentences, and never write a quote you did not find in the text.
            """;

    private static final int MAX_ANSWER = 600;

    private final ChatModel chatModel;
    private final String model;
    private final ObjectMapper json;

    AdvertAsker(ChatModel chatModel, String model, ObjectMapper json) {
        this.chatModel = chatModel;
        this.model = model;
        this.json = json;
    }

    /**
     * What the advert says, or that it says nothing.
     *
     * <p>A model that fails answers "silent" rather than throwing, the same shape the judge
     * has: one unreachable endpoint must not turn a detail page into an error page.
     */
    AdvertAnswer ask(String advert, AdvertQuestion question) {
        String content;
        try {
            content = Answers.textOf(ChatClient.create(chatModel)
                    .prompt()
                    .system(INSTRUCTIONS)
                    .user(describe(advert, question))
                    .call()
                    .chatResponse());
        } catch (RuntimeException e) {
            log.warn("Asking the advert about '{}' failed: {}", question.key(), e.getMessage());
            return AdvertAnswer.silent(question, model);
        }
        return parse(content, advert, question);
    }

    /** The question and the advert, in that order, so the instruction is read against it. */
    static String describe(String advert, AdvertQuestion question) {
        return "Question: " + question.text() + "\n\nAdvert:\n" + advert;
    }

    /**
     * The model's answer, kept only where it can be checked.
     *
     * <p>Four ways to arrive at "silent", and all four are the same answer to the reader: the
     * model said so, it answered in a shape this cannot read, it claimed something without a
     * quote, or it quoted a sentence the advert does not contain. The last one is the reason
     * this method exists.
     */
    AdvertAnswer parse(String content, String advert, AdvertQuestion question) {
        JsonNode node;
        try {
            node = json.readTree(Answers.objectIn(content));
        } catch (Exception e) {
            log.warn("The answer about '{}' was not JSON: {}", question.key(), Answers.abbreviate(content));
            return AdvertAnswer.silent(question, model);
        }
        if (!node.path("stated").asBoolean(false)) {
            return AdvertAnswer.silent(question, model);
        }

        String answer = node.path("answer").asText("").strip();
        String quote = node.path("quote").asText("").strip();
        if (answer.isEmpty() || quote.isEmpty()) {
            log.info("A claim about '{}' arrived without both an answer and a quote; dropped", question.key());
            return AdvertAnswer.silent(question, model);
        }
        if (!contains(advert, quote)) {
            // The failure worth naming in the log, because it is the one a reader could not
            // have caught: a fluent, specific sentence about their advert that is not in it.
            log.info(
                    "The quote answering '{}' is not in the advert, so the claim is dropped: {}",
                    question.key(),
                    Answers.abbreviate(quote));
            return AdvertAnswer.silent(question, model);
        }

        return new AdvertAnswer(
                question.key(),
                true,
                answer.length() <= MAX_ANSWER ? answer : answer.substring(0, MAX_ANSWER),
                quote,
                model);
    }

    /**
     * Whether the advert really contains that sentence.
     *
     * <p>Folded on both sides, so punctuation, casing and collapsed whitespace do not decide
     * it — those are the ways a faithful quote legitimately differs. A quote of fewer than a
     * few words is refused outright: "remote" appears in most adverts and would let anything
     * through.
     */
    static boolean contains(String advert, String quote) {
        String foldedQuote = TextFold.fold(quote);
        return foldedQuote.length() >= 12 && TextFold.fold(advert).contains(foldedQuote);
    }
}
