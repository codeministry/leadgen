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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;

/**
 * The models the {@code anthropic} kind lists, paged through {@code has_more} and {@code last_id}.
 *
 * <p><b>Every listed model counts as tool-capable.</b> This kind publishes no capability field,
 * and every model it lists takes tools; {@code ChatModelCatalogTest} pins the assumption, so the
 * day a listed model refuses a tool round it fails at the model call and ends the turn rather
 * than being guessed around here.
 *
 * <p>The base URL is the host root, without {@code /v1}, the same convention {@code ChatModels}
 * and {@code AnthropicJudge} follow; a value that already ends in {@code /v1} is not doubled.
 */
@RequiredArgsConstructor
final class AnthropicModels implements ChatModelCatalog.Lister {

    /** Pinned for the same reason as in {@code AnthropicJudge}: an unversioned request is refused. */
    private static final String API_VERSION = "2023-06-01";

    /** The most a page may hold; the listing is short, so this is one page in practice. */
    private static final int PAGE_SIZE = 1000;

    /** A bound on the paging, so a server that always says {@code has_more} cannot hold the list forever. */
    private static final int MAX_PAGES = 20;

    private final ChatModelCatalog.Discovery discovery;

    @Override
    public List<String> list(PipelineConfig.Llm llm) throws IOException, InterruptedException {
        String root = ChatModelCatalog.Discovery.trimSlash(llm.baseUrl());
        String models = (root.endsWith("/v1") ? root : root + "/v1") + "/models?limit=" + PAGE_SIZE;
        // The URI first, so a malformed base URL reads as one whatever the key holds; then the
        // header's precondition, enforced where it is built: a key that fits a header.
        URI first = ChatModelCatalog.Discovery.uri(models);
        String key = ChatModelCatalog.Discovery.key(llm.apiKey());
        List<String> names = new ArrayList<>();
        String after = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            URI url = after == null
                    ? first
                    : ChatModelCatalog.Discovery.uri(
                            models + "&after_id=" + URLEncoder.encode(after, StandardCharsets.UTF_8));
            JsonNode body = discovery.send(discovery
                    .request(url)
                    .header("x-api-key", key)
                    .header("anthropic-version", API_VERSION)
                    .GET()
                    .build());
            for (JsonNode model : body.path("data")) {
                String id = model.path("id").asText("");
                if (!id.isBlank()) {
                    names.add(id);
                }
            }
            after = body.path("last_id").asText("");
            if (!body.path("has_more").asBoolean(false) || after.isBlank()) {
                break;
            }
        }
        return names;
    }
}
