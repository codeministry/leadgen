/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;

import de.codeministry.leadgen.chat.ChatCapability;
import de.codeministry.leadgen.chat.ChatCapabilityView;
import de.codeministry.leadgen.chat.ChatContextItem;
import de.codeministry.leadgen.chat.ChatContextKind;
import de.codeministry.leadgen.chat.ChatTurnService;
import de.codeministry.leadgen.chat.ConversationRepository;
import de.codeministry.leadgen.chat.suggest.FollowUp;
import de.codeministry.leadgen.chat.suggest.FollowUps;
import de.codeministry.leadgen.chat.suggest.Suggestion;
import de.codeministry.leadgen.chat.suggest.SuggestionScope;
import de.codeministry.leadgen.chat.suggest.SuggestionService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * ISC-421, the endpoint the header reads before it draws the button: present with a model,
 * absent without one.
 */
@WebMvcTest(ChatController.class)
class ChatControllerTest {

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private ChatCapability capability;

    @MockitoBean
    private ConversationRepository conversations;

    /** The turn itself is proven over real HTTP in ChatStreamTest; this slice only needs it to exist. */
    @MockitoBean
    private ChatTurnService turns;

    @MockitoBean
    private SuggestionService suggestions;

    @MockitoBean
    private FollowUps followUps;

    @Test
    void saysPresentWhenAModelAnswers() {
        given(capability.view()).willReturn(new ChatCapabilityView(true));

        assertThat(mvc.get().uri("/api/v1/chat/capability"))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.present")
                .isEqualTo(true);
    }

    @Test
    void saysAbsentWhenNoModelIsConfigured() {
        given(capability.view()).willReturn(new ChatCapabilityView(false));

        assertThat(mvc.get().uri("/api/v1/chat/capability"))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.present")
                .isEqualTo(false);
    }

    /**
     * A turn is a stream only if nothing between here and the browser holds it back: nginx
     * buffers a proxied response unless the upstream says {@code X-Accel-Buffering: no}, and a
     * cache may keep an event stream it was not told to leave alone.
     */
    @Test
    void anAskedTurnTellsEveryProxyNotToBufferTheStream() {
        given(conversations.exists(1L)).willReturn(true);
        given(capability.model()).willReturn(Optional.of("chat-model"));
        willAnswer(call -> {
                    call.<Runnable>getArgument(3).run();
                    return null;
                })
                .given(turns)
                .start(eq(1L), anyString(), any(), any());

        var result = mvc.post()
                .uri("/api/v1/chat/conversations/1/turns")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"Anything?\"}")
                .exchange();

        assertThat(result.getResponse().getHeader("X-Accel-Buffering")).isEqualTo("no");
        assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-cache");
    }

    /**
     * ISC-456, the seam: {@code ?context=} in the URL's {@code chatCtx} form scopes the suggestions
     * to its offer pins, skipping every other entry, and {@code Accept-Language} picks the language.
     */
    @Test
    void suggestionsAreScopedByTheContextParameterInTheReadersLanguage() {
        SuggestionScope pinned =
                new SuggestionScope(5L, List.of(new ChatContextItem(ChatContextKind.OFFER, 5L, null, null, null)));
        given(suggestions.suggestions(eq(pinned), eq("de")))
                .willReturn(List.of(new Suggestion("shortlist", "Welches passt?", 3)));

        assertThat(mvc.get()
                        .uri("/api/v1/chat/suggestions?context=o:5,v:x,w:y")
                        .header("Accept-Language", "de-DE,de;q=0.9,en;q=0.8"))
                .hasStatusOk()
                .bodyJson()
                .isLenientlyEqualTo("[{\"trigger\":\"shortlist\",\"text\":\"Welches passt?\",\"count\":3}]");
    }

    /** A conversation that does not exist has no suggestions to scope: 404, not the unscoped set. */
    @Test
    void suggestionsForAMissingConversationAreNotFound() {
        given(conversations.find(9L)).willReturn(Optional.empty());

        assertThat(mvc.get().uri("/api/v1/chat/suggestions?conversation=9")).hasStatus(404);
    }

    /** ISC-457, the seam: the follow-ups as `[{text}]`, English without a header; 404 for a turn not there. */
    @Test
    void followUpsAreAnsweredAndAMissingTurnIsNotFound() {
        given(followUps.of(1L, 2L, "en"))
                .willReturn(Optional.of(List.of(new FollowUp("Compare offer #4 with offer #5."))));
        given(followUps.of(1L, 3L, "en")).willReturn(Optional.empty());

        assertThat(mvc.get().uri("/api/v1/chat/conversations/1/turns/2/followups"))
                .hasStatusOk()
                .bodyJson()
                .isLenientlyEqualTo("[{\"text\":\"Compare offer #4 with offer #5.\"}]");
        assertThat(mvc.get().uri("/api/v1/chat/conversations/1/turns/3/followups"))
                .hasStatus(404);
    }

    /** An empty search is no search: the list as before, not the 501 of a query. */
    @Test
    void aBlankQueryListsEveryConversation() {
        given(conversations.list()).willReturn(java.util.List.of());

        assertThat(mvc.get().uri("/api/v1/chat/conversations?q=")).hasStatusOk();
    }

    /** A context item of a kind the server does not know is the client's error, not the server's. */
    @Test
    void aContextOfAnUnknownKindIsABadRequest() {
        assertThat(mvc.post()
                        .uri("/api/v1/chat/conversations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"context\":[{\"kind\":\"TAG\"}]}"))
                .hasStatus(400);
    }

    @Test
    void aRegeneratedTurnTellsEveryProxyNotToBufferTheStream() {
        given(capability.model()).willReturn(Optional.of("chat-model"));
        willAnswer(call -> {
                    call.<Runnable>getArgument(3).run();
                    return true;
                })
                .given(turns)
                .regenerate(eq(1L), eq(2L), any(), any());

        var result = mvc.post()
                .uri("/api/v1/chat/conversations/1/turns/2/regenerate")
                .exchange();

        assertThat(result.getResponse().getHeader("X-Accel-Buffering")).isEqualTo("no");
        assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-cache");
    }
}
