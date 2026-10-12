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
import de.codeministry.leadgen.config.model.PipelineConfig;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;

/**
 * The models an {@code openai-compatible} endpoint lists at {@code models}, relative to its base.
 *
 * <p>The base URL already carries the API's version segment ({@code …/v1}): the chat model is
 * built on it and appends {@code /chat/completions}, so the list sits beside that path.
 *
 * <p><b>Every listed model counts as tool-capable</b>, because the wire format carries no
 * capability field. A model that turns out not to take tools fails at the model call and ends the
 * turn; nothing here guesses from its name.
 */
@RequiredArgsConstructor
final class OpenAiCompatibleModels implements ChatModelCatalog.Lister {

    private final ChatModelCatalog.Discovery discovery;

    @Override
    public List<String> list(PipelineConfig.Llm llm) throws IOException, InterruptedException {
        HttpRequest.Builder request =
                discovery.request(URI.create(ChatModelCatalog.Discovery.trimSlash(llm.baseUrl()) + "/models"));
        // A local server wants no key, and an empty bearer is a header some of them refuse.
        if (llm.apiKey() != null && !llm.apiKey().isBlank()) {
            request.header("Authorization", "Bearer " + llm.apiKey());
        }
        JsonNode body = discovery.send(request.GET().build());
        List<String> names = new ArrayList<>();
        for (JsonNode model : body.path("data")) {
            String id = model.path("id").asText("");
            if (!id.isBlank()) {
                names.add(id);
            }
        }
        return names;
    }
}
