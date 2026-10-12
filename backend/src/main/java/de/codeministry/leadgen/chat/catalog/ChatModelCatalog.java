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
import com.fasterxml.jackson.databind.ObjectMapper;
import de.codeministry.leadgen.config.ConfigRegistry;
import de.codeministry.leadgen.config.ConfigSnapshot;
import de.codeministry.leadgen.config.model.PipelineConfig;
import de.codeministry.leadgen.llm.ChatModels;
import de.codeministry.leadgen.llm.Connections;
import de.codeministry.leadgen.llm.Connections.Connection;
import de.codeministry.leadgen.llm.ModelChoice;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * What the chat's model select offers: per configured connection, the models it reports that can
 * call tools (spec 025, ISC-505). Read from the connection itself, never from a list in
 * {@code .env}, so a model pulled or released yesterday is offered today.
 *
 * <p><b>A connection that does not answer never takes the other one down.</b> Whatever goes wrong
 * on one — a refused connection, a non-2xx, no answer within {@link #DISCOVERY_TIMEOUT} — becomes
 * that connection listed as unreachable, with its reason and no models.
 *
 * <p><b>Cached per configuration snapshot for {@link #TTL}.</b> {@link #models()} refreshes a
 * stale list; a reload hands out a new snapshot object, and the list read under the old one goes
 * with it. {@link #contains} reads the cached list and never fetches, because a turn must not wait
 * on a model server's listing before it starts.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatModelCatalog {

    /** How long a read list is served before {@link #models()} asks the connections again. */
    static final Duration TTL = Duration.ofSeconds(60);

    /**
     * The discovery's own bound, per request, and deliberately not {@code llm.timeout}: that one is
     * sized for a model thinking over a long prompt, and a listing that takes that long is a select
     * that never opens.
     */
    static final Duration DISCOVERY_TIMEOUT = Duration.ofSeconds(5);

    private final ConfigRegistry config;
    private final Discovery discovery = new Discovery();
    private final AtomicReference<Cached> cached = new AtomicReference<>();

    /** Every configured connection with its tool-capable models, from the cache while it is fresh. */
    public synchronized ChatModelsView models() {
        ConfigSnapshot snapshot = config.snapshot();
        Cached current = cached.get();
        long now = System.nanoTime();
        if (current != null && current.snapshot() == snapshot && now - current.readAt() < TTL.toNanos()) {
            return current.view();
        }
        ChatModelsView view = read(snapshot.application());
        cached.set(new Cached(snapshot, now, view));
        return view;
    }

    /**
     * Whether the last list read under the current configuration offered {@code model} on
     * {@code connection}. Never fetches: before any list was read, or after a reload, the answer
     * is {@code false}. An aged list still answers, since the drawer offered exactly that list.
     */
    public boolean contains(String connection, String model) {
        Cached current = cached.get();
        if (current == null || current.snapshot() != config.snapshot() || connection == null || model == null) {
            return false;
        }
        return current.view().connections().stream()
                .filter(c -> c.name().equals(connection))
                .flatMap(c -> c.models().stream())
                .anyMatch(m -> m.name().equals(model));
    }

    private ChatModelsView read(PipelineConfig pipeline) {
        Optional<String> defaultModel =
                ModelChoice.chat(pipeline.llm() == null ? null : pipeline.llm().models());
        List<ConnectionView> connections = new ArrayList<>(2);
        for (Connection connection : Connections.all(pipeline)) {
            connections.add(
                    read(connection, connection.name() == Connections.Name.PRIMARY ? defaultModel : Optional.empty()));
        }
        return new ChatModelsView(List.copyOf(connections));
    }

    private ConnectionView read(Connection connection, Optional<String> defaultModel) {
        String name = connection.name().name();
        try {
            Lister lister = lister(connection.kind());
            if (connection.llm().baseUrl() == null || connection.llm().baseUrl().isBlank()) {
                throw new IOException("base_url is not set");
            }
            List<ModelView> models = lister.list(connection.llm()).stream()
                    .distinct()
                    .map(model -> new ModelView(model, isDefault(connection.kind(), model, defaultModel)))
                    .toList();
            return new ConnectionView(name, connection.kind(), connection.billed(), true, null, models);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return unreachable(connection, "the listing was interrupted");
        } catch (IOException | RuntimeException e) {
            String reason = reason(e);
            log.warn("The {} connection ({}) lists no models for the chat: {}", name, connection.kind(), reason);
            return unreachable(connection, reason);
        }
    }

    private Lister lister(String kind) throws IOException {
        return switch (kind == null ? "" : kind) {
            case ChatModels.OLLAMA -> new OllamaModels(discovery);
            case ChatModels.ANTHROPIC -> new AnthropicModels(discovery);
            case ChatModels.OPENAI_COMPATIBLE -> new OpenAiCompatibleModels(discovery);
            default -> throw new IOException("provider kind '" + kind + "' cannot list its models");
        };
    }

    /**
     * Whether {@code model} is the chat's default. Ollama reports a model with its tag, so a
     * configured {@code name} without one is the same model as {@code name:latest}.
     */
    private static boolean isDefault(String kind, String model, Optional<String> defaultModel) {
        if (defaultModel.isEmpty()) {
            return false;
        }
        String wanted = defaultModel.get();
        return model.equals(wanted) || (ChatModels.OLLAMA.equals(kind) && model.equals(wanted + ":latest"));
    }

    private static ConnectionView unreachable(Connection connection, String reason) {
        return new ConnectionView(
                connection.name().name(), connection.kind(), connection.billed(), false, reason, List.of());
    }

    /** Why a listing failed, in words the select can show; never the key, never a stack trace. */
    private static String reason(Exception e) {
        if (e instanceof HttpTimeoutException) {
            return "no answer within " + DISCOVERY_TIMEOUT.toSeconds() + " s";
        }
        if (e instanceof ConnectException) {
            return "the connection was refused";
        }
        if (e instanceof IllegalArgumentException) {
            return "base_url is not a valid URL";
        }
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    /** One list as read under one configuration snapshot, and when. */
    private record Cached(ConfigSnapshot snapshot, long readAt, ChatModelsView view) {}

    /** One provider kind's way of listing the models a chat may pick on a connection. */
    interface Lister {

        /** The tool-capable models {@code llm} reports, in its order; any failure is thrown. */
        List<String> list(PipelineConfig.Llm llm) throws IOException, InterruptedException;
    }

    /** The HTTP client every adapter shares, bounded by {@link #DISCOVERY_TIMEOUT}. */
    static final class Discovery {

        private final HttpClient http = HttpClient.newBuilder()
                .connectTimeout(DISCOVERY_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        private final ObjectMapper json = new ObjectMapper();

        HttpRequest.Builder request(URI uri) {
            return HttpRequest.newBuilder(uri).timeout(DISCOVERY_TIMEOUT).header("Accept", "application/json");
        }

        /** The body as JSON, or an {@link IOException} naming the path and the status for a non-2xx. */
        JsonNode send(HttpRequest request) throws IOException, InterruptedException {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IOException(
                        request.method() + " " + request.uri().getPath() + " answered HTTP " + response.statusCode());
            }
            return json.readTree(response.body());
        }

        /** {@code url} without its trailing slashes; blank stays blank. */
        static String trimSlash(String url) {
            String trimmed = url == null ? "" : url.trim();
            while (trimmed.endsWith("/")) {
                trimmed = trimmed.substring(0, trimmed.length() - 1);
            }
            return trimmed;
        }
    }
}
