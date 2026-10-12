/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.chat.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import de.codeministry.leadgen.config.model.PipelineConfig;
import de.codeministry.leadgen.llm.ChatModels;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;

/**
 * The listers that set a credential header enforce its precondition themselves, whoever calls
 * them, and either way no request is sent.
 *
 * <p>Through the catalog a blank key never reaches a lister: {@code Providers.missing} words it
 * {@code NO_KEY} first. Called directly with one, a lister refuses with an
 * {@link IllegalArgumentException} whose message is fixed — never {@code Bearer null}, never an
 * empty key. A key that cannot go into a header is {@code INVALID_KEY}, and a malformed base URL is
 * {@code INVALID_BASE_URL} whatever the key holds, because the URI is checked first.
 */
class KeyedListersTest {

    private static final Function<ChatModelCatalog.Discovery, ChatModelCatalog.Lister> ANTHROPIC = AnthropicModels::new;
    private static final Function<ChatModelCatalog.Discovery, ChatModelCatalog.Lister> COMPATIBLE =
            OpenAiCompatibleModels::new;

    static Stream<Arguments> blankKeys() {
        return Stream.of(
                Arguments.of(ChatModels.ANTHROPIC, ANTHROPIC, null),
                Arguments.of(ChatModels.ANTHROPIC, ANTHROPIC, " "),
                Arguments.of(ChatModels.OPENAI_COMPATIBLE, COMPATIBLE, null),
                Arguments.of(ChatModels.OPENAI_COMPATIBLE, COMPATIBLE, ""));
    }

    static Stream<Arguments> invalidKeys() {
        return Stream.of(
                Arguments.of(ChatModels.ANTHROPIC, ANTHROPIC, "key\u0000"),
                Arguments.of(ChatModels.OPENAI_COMPATIBLE, COMPATIBLE, "keyä"));
    }

    static Stream<Arguments> listers() {
        return Stream.of(
                Arguments.of(ChatModels.ANTHROPIC, ANTHROPIC), Arguments.of(ChatModels.OPENAI_COMPATIBLE, COMPATIBLE));
    }

    @ParameterizedTest
    @MethodSource("blankKeys")
    void aBlankKeyIsACallerThatSkippedProvidersMissingAndNoRequestIsSent(
            String kind, Function<ChatModelCatalog.Discovery, ChatModelCatalog.Lister> lister, String key)
            throws Exception {
        ChatModelCatalog.Discovery discovery = Mockito.spy(new ChatModelCatalog.Discovery());
        var llm = new PipelineConfig.Llm(kind, "http://127.0.0.1:9/v1", key, null, false, null, null);

        var refused = catchThrowableOfType(
                IllegalArgumentException.class, () -> lister.apply(discovery).list(llm));

        assertThat(refused).hasMessage(ChatModelCatalog.Discovery.KEY_REQUIRED);
        verify(discovery, never()).send(any());
    }

    @ParameterizedTest
    @MethodSource("invalidKeys")
    void aKeyThatCannotGoIntoTheHeaderIsRefusedBeforeAnyRequest(
            String kind, Function<ChatModelCatalog.Discovery, ChatModelCatalog.Lister> lister, String key)
            throws Exception {
        ChatModelCatalog.Discovery discovery = Mockito.spy(new ChatModelCatalog.Discovery());
        var llm = new PipelineConfig.Llm(kind, "http://127.0.0.1:9/v1", key, null, false, null, null);

        var refused = catchThrowableOfType(
                ChatModelCatalog.ListingException.class,
                () -> lister.apply(discovery).list(llm));

        assertThat(refused.reason()).isEqualTo(ChatModelCatalog.INVALID_KEY);
        verify(discovery, never()).send(any());
    }

    @ParameterizedTest
    @MethodSource("listers")
    void aMalformedBaseUrlIsReportedBeforeTheKeyIsLookedAt(
            String kind, Function<ChatModelCatalog.Discovery, ChatModelCatalog.Lister> lister) throws Exception {
        ChatModelCatalog.Discovery discovery = Mockito.spy(new ChatModelCatalog.Discovery());
        var llm = new PipelineConfig.Llm(kind, "ftp://127.0.0.1:9/v1", "key\u0000", null, false, null, null);

        var refused = catchThrowableOfType(
                ChatModelCatalog.ListingException.class,
                () -> lister.apply(discovery).list(llm));

        assertThat(refused.reason()).isEqualTo(ChatModelCatalog.INVALID_BASE_URL);
        verify(discovery, never()).send(any());
    }
}
