/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.chat.suggest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.codeministry.leadgen.chat.ChatBudget;
import de.codeministry.leadgen.chat.ChatCapability;
import de.codeministry.leadgen.config.ConfigProperties;
import de.codeministry.leadgen.config.ConfigRegistry;
import de.codeministry.leadgen.llm.ChatModels;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import reactor.core.publisher.Flux;

/**
 * {@link Phrasing} without a context: what happens when one of its maps reaches
 * {@link Phrasing#CACHE_LIMIT}, and what the follow-ups' path says about a model it cannot build.
 */
@ExtendWith(OutputCaptureExtension.class)
class PhrasingWithoutAContextTest {

    private static final Map<String, String> SENTENCES = Map.of("open", "1 application is open. Where does it stand?");

    private final ChatCapability capability = mock(ChatCapability.class);
    private final ChatModels chatModels = mock(ChatModels.class);
    private final ConfigRegistry config = mock(ConfigRegistry.class, RETURNS_DEEP_STUBS);
    private final ChatBudget budget = mock(ChatBudget.class);
    private final PhrasingStore store = mock(PhrasingStore.class);
    private final ConfigProperties properties = mock(ConfigProperties.class, RETURNS_DEEP_STUBS);

    private Phrasing phrasing;

    @BeforeEach
    void wire() {
        when(capability.model()).thenReturn(Optional.of("model"));
        // No llm block: the call falls back to its default timeout.
        when(config.snapshot().application().llm()).thenReturn(null);
        when(properties.chat().suggestionRetryAfter()).thenReturn(Duration.ofMinutes(5));
        phrasing = new Phrasing(capability, chatModels, config, budget, store, properties);
    }

    @Test
    void aFullSkipListMakesRoomForOneKeyAndKeepsEveryOtherWindow() {
        // The model is named but cannot be built: every attempt is skipped for the retry window.
        when(chatModels.of(any(), any())).thenReturn(Optional.empty());
        List<String> keys = IntStream.rangeClosed(0, Phrasing.CACHE_LIMIT)
                .mapToObj(i -> phrasing.key("suggestions " + i, SENTENCES, "en").orElseThrow())
                .toList();

        for (int i = 0; i <= Phrasing.CACHE_LIMIT; i++) {
            phrasing.attempt("suggestions " + i, SENTENCES, "en");
        }

        assertThat(keys.stream().filter(phrasing::skipped))
                .as("one more skip than the bound drops one window, not all of them")
                .hasSize(Phrasing.CACHE_LIMIT);
        assertThat(phrasing.skipped(keys.getLast())).isTrue();
    }

    @Test
    void followUpsNeverPushASuggestionPhrasingOutOfMemory() {
        String suggestion = phrasing.key("suggestions 1", SENTENCES, "en").orElseThrow();
        when(store.find(suggestion)).thenReturn(Optional.of(SENTENCES));
        assertThat(phrasing.stored(suggestion)).isPresent();

        ChatModel model = mock(ChatModel.class);
        when(model.stream(any(Prompt.class)))
                .thenAnswer(call -> Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("{}"))))));
        when(chatModels.of(any(), any())).thenReturn(Optional.of(model));
        when(budget.take()).thenReturn(true);
        for (int turn = 0; turn <= Phrasing.CACHE_LIMIT; turn++) {
            phrasing.phrase("followups " + turn, SENTENCES, "en");
        }

        assertThat(phrasing.stored(suggestion)).isPresent();
        verify(store, times(1).description("the suggestion is still in memory, not read from the table again"))
                .find(suggestion);
    }

    @Test
    void aFollowUpWhoseModelCannotBeBuiltSaysSoAndShowsTheCatalog(CapturedOutput output) {
        when(chatModels.of(any(), any())).thenReturn(Optional.empty());

        assertThat(phrasing.phrase("followups 1", SENTENCES, "en")).isEqualTo(SENTENCES);
        assertThat(output.getOut()).contains("is named but could not be built; the follow-ups show");
    }
}
