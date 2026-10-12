/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.chat.catalog;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import de.codeministry.leadgen.config.model.PipelineConfig;
import de.codeministry.leadgen.llm.ChatModels;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * ISC-505: one model the Ollama server cannot describe is left out, and the connection still lists
 * the others — whether the describe failed with a checked exception or an unchecked one. The list
 * it came from did answer, so the connection is not "could not be reached".
 */
class OllamaModelsTest {

    private final WireMockServer server =
            new WireMockServer(WireMockConfiguration.options().dynamicPort());

    @BeforeEach
    void start() {
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop();
    }

    @Test
    void aModelThatCannotBeDescribedIsSkippedAndTheOthersAreListed() throws Exception {
        server.stubFor(get(urlPathEqualTo("/api/tags"))
                .willReturn(okJson("{\"models\":[{\"name\":\"model-a\"},{\"name\":\"model-b\"},"
                        + "{\"name\":\"model-c\"},{\"name\":\"model-d\"}]}")));
        describe("model-a", "{\"capabilities\":[\"tools\"]}");
        // Read, and then refused by something past the parser: an unchecked failure on one model.
        describe("model-b", "{\"capabilities\":[\"tools\"],\"unexpected\":true}");
        // Not JSON at all: a checked failure on one model.
        server.stubFor(post(urlPathEqualTo("/api/show"))
                .withRequestBody(matchingJsonPath("$.model", equalTo("model-c")))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{not json")));
        describe("model-d", "{\"capabilities\":[\"tools\"]}");

        ChatModelCatalog.Discovery discovery = Mockito.spy(new ChatModelCatalog.Discovery());
        doAnswer(invocation -> {
                    JsonNode answer = (JsonNode) invocation.callRealMethod();
                    if (answer.has("unexpected")) {
                        throw new IllegalStateException("describing this model failed unexpectedly");
                    }
                    return answer;
                })
                .when(discovery)
                .send(any());

        var llm = new PipelineConfig.Llm(ChatModels.OLLAMA, server.baseUrl() + "/v1", null, null, false, null, null);

        assertThat(new OllamaModels(discovery).list(llm)).containsExactly("model-a", "model-d");
        server.verify(4, postRequestedFor(urlPathEqualTo("/api/show")));
    }

    private void describe(String model, String body) {
        server.stubFor(post(urlPathEqualTo("/api/show"))
                .withRequestBody(matchingJsonPath("$.model", equalTo(model)))
                .willReturn(okJson(body)));
    }
}
