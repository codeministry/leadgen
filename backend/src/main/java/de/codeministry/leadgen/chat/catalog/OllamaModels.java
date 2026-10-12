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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * The models an Ollama server holds that can call tools, read from its native API.
 *
 * <p>The chat talks to Ollama on its OpenAI-compatible path, so the configured base URL ends in
 * {@code /v1}; the native API lives at the root beside it. {@code /api/tags} lists what is
 * pulled, and {@code /api/show} is asked per model, at most {@link #SHOW_PARALLELISM} at a time,
 * because only it names the capabilities: a model without {@code tools} among them would refuse
 * the chat's first tool round, so it is not offered at all.
 */
@Slf4j
@RequiredArgsConstructor
final class OllamaModels implements ChatModelCatalog.Lister {

    /** How many {@code /api/show} requests run at once. */
    static final int SHOW_PARALLELISM = 4;

    private final ChatModelCatalog.Discovery discovery;

    @Override
    public List<String> list(PipelineConfig.Llm llm) throws IOException, InterruptedException {
        String root = nativeRoot(llm.baseUrl());
        URI showUri = ChatModelCatalog.Discovery.uri(root + "/api/show");
        JsonNode tags = discovery.send(discovery
                .request(ChatModelCatalog.Discovery.uri(root + "/api/tags"))
                .GET()
                .build());
        List<String> names = new ArrayList<>();
        for (JsonNode entry : tags.path("models")) {
            String name = entry.path("name").asText("");
            if (!name.isBlank()) {
                names.add(name);
            }
        }
        if (names.isEmpty()) {
            return List.of();
        }
        // A few descriptions at a time: one after the other, forty pulled models are forty round
        // trips before the select opens; all at once, they are forty requests against one local server.
        ExecutorService executor = Executors.newFixedThreadPool(
                Math.min(SHOW_PARALLELISM, names.size()), Thread.ofVirtual().factory());
        try {
            List<Future<Boolean>> described = new ArrayList<>(names.size());
            for (String name : names) {
                described.add(executor.submit(() -> callsTools(showUri, name)));
            }
            List<String> capable = new ArrayList<>();
            for (int i = 0; i < names.size(); i++) {
                if (answer(names.get(i), described.get(i))) {
                    capable.add(names.get(i));
                }
            }
            return capable;
        } finally {
            // Every description is in on the way out; on an interrupt or an Error, the queued ones never start.
            executor.shutdownNow();
        }
    }

    /**
     * The answer of one description. A failure the task did not catch itself skips that one model
     * with a WARN as well — the list it came from did answer. An {@link Error} is not a model.
     */
    private static boolean answer(String name, Future<Boolean> described) throws InterruptedException {
        try {
            return described.get();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof Error error) {
                throw error;
            }
            // The class only: a message or a stack can quote a request header.
            Throwable failure = e.getCause() == null ? e : e.getCause();
            log.warn(
                    "Ollama could not describe model '{}', so it is not offered to the chat ({})",
                    name,
                    failure.getClass().getName());
            return false;
        }
    }

    /**
     * Whether {@code /api/show} lists {@code tools} for {@code name}. One model the server cannot
     * describe — whatever went wrong, checked or not — is left out with a WARN rather than taking the
     * whole connection down: the list it came from did answer.
     */
    private boolean callsTools(URI showUri, String name) throws InterruptedException {
        String body = JsonNodeFactory.instance.objectNode().put("model", name).toString();
        try {
            JsonNode show = discovery.send(discovery
                    .request(showUri)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build());
            for (JsonNode capability : show.path("capabilities")) {
                if ("tools".equals(capability.asText())) {
                    return true;
                }
            }
            return false;
        } catch (IOException | RuntimeException e) {
            // The class only: a message or a stack can quote a request header.
            log.warn(
                    "Ollama could not describe model '{}', so it is not offered to the chat ({})",
                    name,
                    e.getClass().getName());
            return false;
        }
    }

    /** {@code base_url} without its trailing slash and its trailing {@code /v1}, in that order. */
    static String nativeRoot(String baseUrl) {
        String root = ChatModelCatalog.Discovery.trimSlash(baseUrl);
        return root.endsWith("/v1") ? root.substring(0, root.length() - "/v1".length()) : root;
    }
}
