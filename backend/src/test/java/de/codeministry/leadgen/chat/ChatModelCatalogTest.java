/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.chat;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import de.codeministry.leadgen.chat.catalog.ChatModelCatalog;
import de.codeministry.leadgen.chat.catalog.ChatModelsView;
import de.codeministry.leadgen.chat.catalog.ConnectionView;
import de.codeministry.leadgen.chat.catalog.ModelView;
import de.codeministry.leadgen.config.ConfigRegistry;
import de.codeministry.leadgen.config.ConfigSnapshot;
import de.codeministry.leadgen.config.model.PipelineConfig;
import de.codeministry.leadgen.llm.ChatModels;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * ISC-505: the chat lists, per configured connection, the models that connection reports and that
 * can call tools — read from the connection, never from a list in {@code .env}. A connection that
 * does not answer is listed with its reason and no models, and never takes the other one down.
 *
 * <p>Two WireMock servers stand in for the two connections: the primary speaks Ollama's native
 * API, the second the hosted kind's paged model list. Every model name is neutral.
 */
class ChatModelCatalogTest {

    private static final WireMockServer PRIMARY =
            new WireMockServer(WireMockConfiguration.options().dynamicPort());
    private static final WireMockServer SECOND =
            new WireMockServer(WireMockConfiguration.options().dynamicPort());

    static {
        PRIMARY.start();
        SECOND.start();
    }

    private final ConfigRegistry registry = Mockito.mock(ConfigRegistry.class);

    @AfterAll
    static void stop() {
        PRIMARY.stop();
        SECOND.stop();
    }

    @BeforeEach
    void reset() {
        PRIMARY.resetAll();
        SECOND.resetAll();
        ollamaListsThreeModelsOneWithoutTools();
        hostedListsTwoModelsOverTwoPages();
    }

    @Test
    void listsEveryToolCapableModelPerConnectionWithItsFlags() {
        use(ollamaPrimary(), hostedSecond());

        ChatModelsView view = new ChatModelCatalog(registry).models();

        assertThat(view.connections()).extracting(ConnectionView::name).containsExactly("PRIMARY", "SECOND");
        ConnectionView primary = view.connections().get(0);
        assertThat(primary.kind()).isEqualTo(ChatModels.OLLAMA);
        assertThat(primary.billed()).isFalse();
        assertThat(primary.reachable()).isTrue();
        assertThat(primary.reason()).isNull();
        // Three reported, the one without `tools` among its capabilities is absent.
        assertThat(primary.models()).containsExactly(new ModelView("model-a", true), new ModelView("model-b", false));

        ConnectionView second = view.connections().get(1);
        assertThat(second.kind()).isEqualTo(ChatModels.ANTHROPIC);
        assertThat(second.billed()).isTrue();
        assertThat(second.reachable()).isTrue();
        // Two pages, one model each; the default lives on the primary only.
        assertThat(second.models()).containsExactly(new ModelView("model-x", false), new ModelView("model-y", false));

        // Five reported in all, four listed.
        PRIMARY.verify(1, getRequestedFor(urlPathEqualTo("/api/tags")));
        PRIMARY.verify(3, postRequestedFor(urlPathEqualTo("/api/show")));
        SECOND.verify(getRequestedFor(urlPathEqualTo("/v1/models"))
                .withHeader("x-api-key", equalTo("second-key"))
                .withHeader("anthropic-version", matching(".+")));
        SECOND.verify(getRequestedFor(urlPathEqualTo("/v1/models")).withQueryParam("after_id", equalTo("model-x")));
    }

    @Test
    void aConnectionAnsweringAnErrorIsListedWithItsReasonAndTheOtherStillLists() {
        SECOND.resetAll();
        SECOND.stubFor(get(urlPathEqualTo("/v1/models")).willReturn(aResponse().withStatus(500)));
        use(ollamaPrimary(), hostedSecond());

        ChatModelsView view = new ChatModelCatalog(registry).models();

        ConnectionView primary = view.connections().get(0);
        assertThat(primary.reachable()).isTrue();
        assertThat(primary.models()).extracting(ModelView::name).containsExactly("model-a", "model-b");

        ConnectionView second = view.connections().get(1);
        assertThat(second.name()).isEqualTo("SECOND");
        assertThat(second.reachable()).isFalse();
        assertThat(second.reason()).contains("500");
        assertThat(second.models()).isEmpty();
        assertThat(second.billed()).isTrue();
    }

    @Test
    void aConnectionNobodyListensOnIsListedWithItsReason() {
        var gone = new PipelineConfig.ChatConnection(
                ChatModels.ANTHROPIC, "http://127.0.0.1:1", "second-key", null, null, null);
        use(ollamaPrimary(), gone);

        ChatModelsView view = new ChatModelCatalog(registry).models();

        assertThat(view.connections().get(0).models()).hasSize(2);
        assertThat(view.connections().get(1).reachable()).isFalse();
        assertThat(view.connections().get(1).reason()).isNotBlank();
    }

    @Test
    void withoutASecondConnectionOnlyThePrimaryIsListed() {
        use(ollamaPrimary(), null);

        ChatModelsView view = new ChatModelCatalog(registry).models();

        assertThat(view.connections()).extracting(ConnectionView::name).containsExactly("PRIMARY");
    }

    @Test
    void anOpenAiCompatibleConnectionListsEveryModelWithItsBearerKey() {
        SECOND.resetAll();
        SECOND.stubFor(get(urlPathEqualTo("/v1/models"))
                .willReturn(okJson("{\"object\":\"list\",\"data\":[{\"id\":\"model-p\"},{\"id\":\"model-q\"}]}")));
        var compatible = new PipelineConfig.ChatConnection(
                ChatModels.OPENAI_COMPATIBLE, SECOND.baseUrl() + "/v1", "second-key", "true", null, null);
        use(ollamaPrimary(), compatible);

        ConnectionView second =
                new ChatModelCatalog(registry).models().connections().get(1);

        assertThat(second.kind()).isEqualTo(ChatModels.OPENAI_COMPATIBLE);
        assertThat(second.billed()).isTrue();
        assertThat(second.models()).extracting(ModelView::name).containsExactly("model-p", "model-q");
        SECOND.verify(getRequestedFor(urlPathEqualTo("/v1/models"))
                .withHeader("Authorization", equalTo("Bearer second-key")));
    }

    @Test
    void aSecondCallWithinTheMinuteAsksNobodyAndANewSnapshotAsksAgain() {
        use(ollamaPrimary(), hostedSecond());
        var catalog = new ChatModelCatalog(registry);

        catalog.models();
        catalog.models();
        PRIMARY.verify(1, getRequestedFor(urlPathEqualTo("/api/tags")));

        // A reload hands out a different snapshot object, and the cached list goes with the old one.
        use(ollamaPrimary(), hostedSecond());
        catalog.models();
        PRIMARY.verify(2, getRequestedFor(urlPathEqualTo("/api/tags")));
    }

    @Test
    void containsReadsTheCachedListAndNeverFetches() {
        use(ollamaPrimary(), hostedSecond());
        var catalog = new ChatModelCatalog(registry);

        assertThat(catalog.contains("PRIMARY", "model-a")).isFalse();
        PRIMARY.verify(0, getRequestedFor(urlPathEqualTo("/api/tags")));

        catalog.models();

        assertThat(catalog.contains("PRIMARY", "model-a")).isTrue();
        assertThat(catalog.contains("SECOND", "model-y")).isTrue();
        assertThat(catalog.contains("PRIMARY", "model-c")).isFalse();
        assertThat(catalog.contains("SECOND", "model-a")).isFalse();
        assertThat(catalog.contains("ELSEWHERE", "model-a")).isFalse();
        PRIMARY.verify(1, getRequestedFor(urlPathEqualTo("/api/tags")));
    }

    private static void ollamaListsThreeModelsOneWithoutTools() {
        PRIMARY.stubFor(get(urlPathEqualTo("/api/tags"))
                .willReturn(
                        okJson("{\"models\":[{\"name\":\"model-a\"},{\"name\":\"model-b\"},{\"name\":\"model-c\"}]}")));
        for (String name : List.of("model-a", "model-b")) {
            PRIMARY.stubFor(post(urlPathEqualTo("/api/show"))
                    .withRequestBody(matchingJsonPath("$.model", equalTo(name)))
                    .willReturn(okJson("{\"capabilities\":[\"completion\",\"tools\"]}")));
        }
        PRIMARY.stubFor(post(urlPathEqualTo("/api/show"))
                .withRequestBody(matchingJsonPath("$.model", equalTo("model-c")))
                .willReturn(okJson("{\"capabilities\":[\"completion\"]}")));
    }

    private static void hostedListsTwoModelsOverTwoPages() {
        SECOND.stubFor(get(urlPathEqualTo("/v1/models"))
                .withQueryParam("after_id", absent())
                .willReturn(okJson("{\"data\":[{\"id\":\"model-x\",\"type\":\"model\"}],"
                        + "\"has_more\":true,\"first_id\":\"model-x\",\"last_id\":\"model-x\"}")));
        SECOND.stubFor(get(urlPathEqualTo("/v1/models"))
                .withQueryParam("after_id", equalTo("model-x"))
                .willReturn(okJson("{\"data\":[{\"id\":\"model-y\",\"type\":\"model\"}],"
                        + "\"has_more\":false,\"first_id\":\"model-y\",\"last_id\":\"model-y\"}")));
    }

    /** Ollama's chat path is the OpenAI-compatible one under {@code /v1/}; the catalog reads the native root. */
    private static PipelineConfig.Llm ollamaPrimary() {
        return new PipelineConfig.Llm(
                ChatModels.OLLAMA,
                PRIMARY.baseUrl() + "/v1/",
                null,
                null,
                false,
                new PipelineConfig.Llm.Models(null, "model-z", null, null, null, null, null, "model-a"),
                null);
    }

    private static PipelineConfig.ChatConnection hostedSecond() {
        return new PipelineConfig.ChatConnection(
                ChatModels.ANTHROPIC, SECOND.baseUrl(), "second-key", null, null, null);
    }

    /** A fresh snapshot object, as a reload would hand out, served from now on. */
    private void use(PipelineConfig.Llm llm, PipelineConfig.ChatConnection second) {
        ConfigSnapshot snapshot = snapshot(llm, second);
        given(registry.snapshot()).willReturn(snapshot);
    }

    private static ConfigSnapshot snapshot(PipelineConfig.Llm llm, PipelineConfig.ChatConnection second) {
        var pipeline = Mockito.mock(PipelineConfig.class);
        given(pipeline.llm()).willReturn(llm);
        given(pipeline.chat()).willReturn(new PipelineConfig.Chat(null, null, null, second));
        return new ConfigSnapshot(pipeline, null, null, null, null, null);
    }
}
