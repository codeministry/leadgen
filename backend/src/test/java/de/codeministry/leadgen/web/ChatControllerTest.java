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
import de.codeministry.leadgen.chat.ChatTurnService;
import de.codeministry.leadgen.chat.ConversationRepository;
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
