/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.packaging;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * A chat model that answers from a function and counts how often it was asked.
 *
 * <p>No HTTP: what goes over the wire is the wire-format tests' question. This one stands in
 * for "a configured model" where the test is about what the caller does with the answer.
 */
final class StubChatModel implements ChatModel {

    private final Function<Prompt, String> answer;
    private final List<Prompt> prompts = new ArrayList<>();

    private StubChatModel(Function<Prompt, String> answer) {
        this.answer = answer;
    }

    /** Answers every prompt with the same text. */
    static StubChatModel answering(String text) {
        return new StubChatModel(prompt -> text);
    }

    /** Answers with whatever the function makes of the prompt. */
    static StubChatModel answering(Function<Prompt, String> answer) {
        return new StubChatModel(answer);
    }

    /** Throws on every call, the way an unreachable endpoint does. */
    static StubChatModel throwing() {
        return new StubChatModel(prompt -> {
            throw new IllegalStateException("connection refused");
        });
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        prompts.add(prompt);
        return new ChatResponse(List.of(new Generation(new AssistantMessage(answer.apply(prompt)))));
    }

    int calls() {
        return prompts.size();
    }

    /** Everything the last prompt carried, system and user message alike. */
    String lastPrompt() {
        return prompts.isEmpty() ? "" : prompts.getLast().getContents();
    }
}
