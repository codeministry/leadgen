/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.chat.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import de.codeministry.leadgen.config.model.PipelineConfig;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * The models an Ollama server holds that can call tools, read from its native API.
 *
 * <p>The chat talks to Ollama on its OpenAI-compatible path, so the configured base URL ends in
 * {@code /v1}; the native API lives at the root beside it. {@code /api/tags} lists what is
 * pulled, and {@code /api/show} is asked per model, because only it names the capabilities: a
 * model without {@code tools} among them would refuse the chat's first tool round, so it is not
 * offered at all.
 */
@Slf4j
@RequiredArgsConstructor
final class OllamaModels implements ChatModelCatalog.Lister {

    private final ChatModelCatalog.Discovery discovery;

    @Override
    public List<String> list(PipelineConfig.Llm llm) throws IOException, InterruptedException {
        String root = nativeRoot(llm.baseUrl());
        JsonNode tags = discovery.send(
                discovery.request(URI.create(root + "/api/tags")).GET().build());
        List<String> capable = new ArrayList<>();
        for (JsonNode entry : tags.path("models")) {
            String name = entry.path("name").asText("");
            if (!name.isBlank() && callsTools(root, name)) {
                capable.add(name);
            }
        }
        return capable;
    }

    /**
     * Whether {@code /api/show} lists {@code tools} for {@code name}. One model the server cannot
     * describe is left out with a WARN rather than taking the whole connection down: the list it
     * came from did answer.
     */
    private boolean callsTools(String root, String name) throws InterruptedException {
        String body = JsonNodeFactory.instance.objectNode().put("model", name).toString();
        try {
            JsonNode show = discovery.send(discovery
                    .request(URI.create(root + "/api/show"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build());
            for (JsonNode capability : show.path("capabilities")) {
                if ("tools".equals(capability.asText())) {
                    return true;
                }
            }
            return false;
        } catch (IOException e) {
            log.warn(
                    "Ollama could not describe model '{}', so it is not offered to the chat: {}", name, e.getMessage());
            return false;
        }
    }

    /** {@code base_url} without its trailing slash and its trailing {@code /v1}, in that order. */
    static String nativeRoot(String baseUrl) {
        String root = ChatModelCatalog.Discovery.trimSlash(baseUrl);
        return root.endsWith("/v1") ? root.substring(0, root.length() - "/v1".length()) : root;
    }
}
