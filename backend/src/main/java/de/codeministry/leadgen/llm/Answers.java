/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.llm;

import org.springframework.ai.chat.model.ChatResponse;

import java.util.stream.Collectors;

/**
 * Reading a language model's answer, once, for everything that asks one a question.
 *
 * <p>Both halves of this were learned the expensive way in the scoring judge and are the
 * kind of mistake that produces a plausible empty result rather than an error, so a second
 * caller re-implementing either would fail exactly as quietly.
 */
public final class Answers {

    private Answers() {
    }

    /**
     * Every generation's text, joined.
     *
     * <p><b>Not {@code call().content()}.</b> Spring AI emits a model's thinking as a
     * generation of its own, ahead of the text, so reading the first generation hands back
     * the reasoning and drops the answer. Measured against a stubbed reply carrying a
     * thinking block: every factor was lost, and the only sign was one WARN saying the
     * answer was unusable.
     *
     * <p>Which generation the object arrived in is then not something the caller has to
     * know — {@link #objectIn} decides by the braces, and a provider that answers with a
     * single generation is unaffected.
     */
    public static String textOf(ChatResponse response) {
        if (response == null || response.getResults() == null) {
            return "";
        }
        return response.getResults().stream()
            .map(generation -> generation.getOutput() == null ? "" : generation.getOutput().getText())
            .filter(text -> text != null && !text.isBlank())
            .collect(Collectors.joining("\n"));
    }

    /**
     * The outermost JSON object in an answer, from the first brace to the last.
     *
     * <p>A model asked for JSON and nothing else fences it anyway, or introduces it with a
     * sentence, and both parse to nothing. Kept rather than delegated to the framework's own
     * cleaner, which strips a fence and nothing else: prose <em>outside</em> a fence is the
     * half that was actually measured, and the braces catch both.
     *
     * <p>Nothing is repaired — a genuinely truncated object still fails to parse, and it
     * should.
     */
    public static String objectIn(String content) {
        if (content == null) {
            return "";
        }
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        return start >= 0 && end > start ? content.substring(start, end + 1) : content;
    }

    /**
     * An answer flattened to one line and cut, for a log entry that has to name what came
     * back without pasting a page of it into the log.
     */
    public static String abbreviate(String content) {
        if (content == null || content.isBlank()) {
            return "<nothing>";
        }
        String flattened = content.strip().replaceAll("\\s+", " ");
        return flattened.length() <= 300 ? flattened : flattened.substring(0, 300) + "…";
    }
}
