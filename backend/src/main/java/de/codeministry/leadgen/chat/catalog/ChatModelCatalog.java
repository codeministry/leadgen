/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.chat.catalog;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.codeministry.leadgen.config.ConfigRegistry;
import de.codeministry.leadgen.config.ConfigSnapshot;
import de.codeministry.leadgen.config.model.PipelineConfig;
import de.codeministry.leadgen.llm.ChatModels;
import de.codeministry.leadgen.llm.Connections;
import de.codeministry.leadgen.llm.Connections.Connection;
import de.codeministry.leadgen.llm.ModelChoice;
import de.codeministry.leadgen.llm.Providers;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
 * on one — a refused connection, a non-2xx, a redirect, no answer within {@link #DISCOVERY_TIMEOUT}
 * — becomes that connection listed as unreachable, with one of a few fixed reasons and no models.
 * A reason never names a host, a URL, a header or a response body, and neither does the server
 * log: it gets the exception's class and the reason at WARN, never the exception's message or
 * stack, because an HTTP client's refusal can quote a request header — the key with it.
 *
 * <p><b>A connection that could not answer a turn is not asked at all.</b> {@link Providers#missing}
 * is the rule the chat model is built by — every kind but Ollama needs a key, every kind a base
 * URL — so a connection it finds lacking is listed unreachable without a request rather than
 * offering models whose every turn would end with {@code MODEL}.
 *
 * <p><b>Cached per configuration snapshot for {@link #TTL}, and no lock is held across the
 * network.</b> {@link #models()} refreshes a stale list; concurrent callers that miss on the same
 * snapshot wait on one discovery rather than each starting their own, and a reload hands out a new
 * snapshot object, so the list read under the old one goes with it. The minute counts from when a
 * discovery finished, so a slow one is served as long as a fast one. {@link #contains} reads the
 * cached list and never fetches, because a turn must not wait on a model server's listing before
 * it starts.
 *
 * <p><b>An interrupted discovery is nobody else's answer.</b> Its connections read "interrupted"
 * only because the caller running it was interrupted, so that caller alone gets that view; it is
 * neither cached nor handed to the callers waiting on it, who ask again instead, under the
 * configuration current by then. A waiter interrupted itself gets the same view, for itself only,
 * and the discovery it waited on goes on.
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

    /** The reasons a connection is listed unreachable with; nothing else ever reaches the client. */
    static final String UNREACHABLE = "the connection could not be reached";

    static final String TIMED_OUT = "the model list did not answer in time";
    static final String REDIRECTED = "the model list answered with a redirect";
    static final String UNREADABLE = "the model list could not be read";
    static final String NO_KEY = "no API key is configured for this connection";
    static final String NO_BASE_URL = "no base URL is configured for this connection";
    static final String INVALID_BASE_URL = "the base URL of this connection is not a valid URL";
    static final String INVALID_KEY = "the API key of this connection is not valid";
    static final String UNLISTABLE_KIND = "this provider kind cannot list its models";
    static final String INTERRUPTED = "the listing was interrupted";

    private final ConfigRegistry config;
    private final Discovery discovery = new Discovery();
    private final AtomicReference<Cached> cached = new AtomicReference<>();

    /**
     * The discovery running per snapshot. Keyed by the snapshot object, so a caller still holding
     * the one before a reload never displaces the discovery the new one is waiting on.
     */
    private final ConcurrentMap<SnapshotKey, CompletableFuture<ChatModelsView>> inFlight = new ConcurrentHashMap<>();

    /**
     * Every configured connection with its tool-capable models, from the cache while it is fresh.
     * On a miss the caller either runs the one discovery for this snapshot or waits on the one
     * already running; neither holds a lock while the connections are asked.
     */
    public ChatModelsView models() {
        while (true) {
            // Read again on every round: a waiter asking again after an interrupted discovery lists
            // the configuration current now, not the one before a reload.
            ConfigSnapshot snapshot = config.snapshot();
            SnapshotKey key = new SnapshotKey(snapshot);
            Cached current = cached.get();
            if (fresh(current, snapshot)) {
                return current.view();
            }
            CompletableFuture<ChatModelsView> mine = new CompletableFuture<>();
            CompletableFuture<ChatModelsView> running = inFlight.putIfAbsent(key, mine);
            if (running == null) {
                return discover(snapshot, key, mine);
            }
            Optional<ChatModelsView> shared = await(running, snapshot);
            if (shared.isPresent()) {
                return shared.get();
            }
            // The discovery waited on was interrupted, which says nothing about the connections.
        }
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

    private static boolean fresh(Cached current, ConfigSnapshot snapshot) {
        return current != null
                && current.snapshot() == snapshot
                && System.nanoTime() - current.readAt() < TTL.toNanos();
    }

    /**
     * Runs the discovery {@code mine} stands for, publishes it, and lets every waiter have it — unless
     * it was interrupted, in which case only this caller gets it. The entry leaves {@link #inFlight}
     * before the waiters are released, so one asking again starts a discovery of its own.
     */
    private ChatModelsView discover(ConfigSnapshot snapshot, SnapshotKey key, CompletableFuture<ChatModelsView> mine) {
        ChatModelsView view;
        boolean interrupted;
        try {
            // Another caller may have published this snapshot's list between the miss and the claim.
            Cached current = cached.get();
            if (fresh(current, snapshot)) {
                view = current.view();
                interrupted = false;
            } else {
                view = read(snapshot.application());
                interrupted = interrupted(view);
                // A list read under a configuration that has since been reloaded is answered to its
                // own callers and never published over the newer one's. Its minute starts now, when
                // it was read, and not when the slowest connection was first asked.
                if (!interrupted && config.snapshot() == snapshot) {
                    cached.set(new Cached(snapshot, System.nanoTime(), view));
                }
            }
        } catch (RuntimeException | Error e) {
            inFlight.remove(key, mine);
            mine.completeExceptionally(e);
            throw e;
        }
        inFlight.remove(key, mine);
        if (interrupted) {
            mine.completeExceptionally(new DiscoveryInterrupted());
        } else {
            mine.complete(view);
        }
        return view;
    }

    /** Whether {@code view} reads unreachable only because the caller reading it was interrupted. */
    private static boolean interrupted(ChatModelsView view) {
        return Thread.currentThread().isInterrupted()
                || view.connections().stream().anyMatch(c -> INTERRUPTED.equals(c.reason()));
    }

    /**
     * The shared discovery's view, or empty when it was interrupted and the waiter should ask again.
     * A waiter interrupted while it waits gets every connection of {@code snapshot} as interrupted,
     * for itself alone: nothing is cached, and the discovery it waited on is left to finish.
     */
    private static Optional<ChatModelsView> await(CompletableFuture<ChatModelsView> view, ConfigSnapshot snapshot) {
        try {
            return Optional.of(view.get());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.of(interruptedView(snapshot.application()));
        } catch (ExecutionException e) {
            if (e.getCause() instanceof DiscoveryInterrupted) {
                return Optional.empty();
            }
            if (e.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            if (e.getCause() instanceof Error error) {
                throw error;
            }
            throw new CompletionException(e.getCause());
        }
    }

    /** Every connection of {@code pipeline} as unreachable because the caller was interrupted. */
    private static ChatModelsView interruptedView(PipelineConfig pipeline) {
        List<ConnectionView> connections = Connections.all(pipeline).stream()
                .map(connection -> unreachable(connection, INTERRUPTED))
                .toList();
        return new ChatModelsView(connections, defaultModel(pipeline).orElse(null));
    }

    private static Optional<String> defaultModel(PipelineConfig pipeline) {
        return ModelChoice.chat(pipeline.llm() == null ? null : pipeline.llm().models());
    }

    /** Both connections at once, so a slow one costs its own time and not the other's as well. */
    private ChatModelsView read(PipelineConfig pipeline) {
        Optional<String> defaultModel = defaultModel(pipeline);
        List<Connection> all = Connections.all(pipeline);
        List<ConnectionView> connections = new ArrayList<>(all.size());
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<ConnectionView>> reads = new ArrayList<>(all.size());
            for (Connection connection : all) {
                Optional<String> flagged =
                        connection.name() == Connections.Name.PRIMARY ? defaultModel : Optional.empty();
                reads.add(executor.submit(() -> read(connection, flagged)));
            }
            for (int i = 0; i < all.size(); i++) {
                connections.add(collect(all.get(i), reads.get(i)));
            }
        }
        return new ChatModelsView(List.copyOf(connections), defaultModel.orElse(null));
    }

    private static ConnectionView collect(Connection connection, Future<ConnectionView> read) {
        try {
            return read.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return unreachable(connection, INTERRUPTED);
        } catch (ExecutionException e) {
            // read(connection, …) catches everything it can name; this is a bug, not a connection.
            // The class and the fixed reason only, as in read(): a message or a stack can quote a
            // request header.
            Throwable failure = e.getCause() == null ? e : e.getCause();
            log.warn(
                    "The {} connection ({}) lists no models for the chat: {} ({})",
                    connection.name(),
                    connection.kind(),
                    UNREADABLE,
                    failure.getClass().getName());
            return unreachable(connection, UNREADABLE);
        }
    }

    private ConnectionView read(Connection connection, Optional<String> defaultModel) {
        String name = connection.name().name();
        try {
            // The chat model is built by the same rule, so nothing a lacking connection lists could
            // answer. Checked before the lister is chosen, so a missing kind is worded here, once.
            Optional<Providers.Missing> missing = Providers.missing(connection.llm());
            if (missing.isPresent()) {
                throw new ListingException(
                        switch (missing.get()) {
                            case PROVIDER -> UNLISTABLE_KIND;
                            case API_KEY -> NO_KEY;
                            case BASE_URL -> NO_BASE_URL;
                        });
            }
            List<ModelView> models = lister(connection.kind()).list(connection.llm()).stream()
                    .distinct()
                    .map(model -> new ModelView(model, isDefault(connection.kind(), model, defaultModel)))
                    .toList();
            return new ConnectionView(name, connection.kind(), connection.billed(), true, null, models);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return unreachable(connection, INTERRUPTED);
        } catch (IOException | RuntimeException e) {
            String reason = reason(e);
            // The class and the fixed reason only: a message or a stack can quote a request header.
            log.warn(
                    "The {} connection ({}) lists no models for the chat: {} ({})",
                    name,
                    connection.kind(),
                    reason,
                    e.getClass().getName());
            return unreachable(connection, reason);
        }
    }

    /** The lister for {@code kind}, which {@link Providers#missing} has already found non-blank. */
    private Lister lister(String kind) throws ListingException {
        return switch (kind) {
            case ChatModels.OLLAMA -> new OllamaModels(discovery);
            case ChatModels.ANTHROPIC -> new AnthropicModels(discovery);
            case ChatModels.OPENAI_COMPATIBLE -> new OpenAiCompatibleModels(discovery);
            default -> throw new ListingException(UNLISTABLE_KIND);
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

    /**
     * Why a listing failed, as one of the fixed reasons above. Never an exception's own message:
     * those name the host, the TLS peer, a request header or a snippet of the body the server
     * answered with. A base URL or a key that cannot go into a request is a {@link ListingException}
     * of its own, decided by {@link Discovery#uri} and {@link Discovery#key} before one is built.
     */
    static String reason(Exception e) {
        return switch (e) {
            case ListingException listing -> listing.reason();
            case HttpTimeoutException _ -> TIMED_OUT;
            case JacksonException _ -> UNREADABLE;
            case IOException _ -> UNREACHABLE;
            default -> UNREADABLE;
        };
    }

    /** One list as read under one configuration snapshot, and when its discovery finished. */
    private record Cached(ConfigSnapshot snapshot, long readAt, ChatModelsView view) {}

    /**
     * A snapshot by identity. {@link ConfigSnapshot} is a record, so its own {@code equals} would
     * compare — and hash — the whole configuration on every call, and could take two reloads that
     * read the same files for one.
     */
    private record SnapshotKey(ConfigSnapshot snapshot) {

        @Override
        public boolean equals(Object other) {
            return other instanceof SnapshotKey key && key.snapshot == snapshot;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(snapshot);
        }
    }

    /** A shared discovery was interrupted; its waiters ask again rather than take its view. */
    private static final class DiscoveryInterrupted extends RuntimeException {

        DiscoveryInterrupted() {
            super(null, null, false, false);
        }
    }

    /** A listing that failed for a reason already worded for the client. */
    static final class ListingException extends IOException {

        private final String reason;

        ListingException(String reason) {
            super(reason);
            this.reason = reason;
        }

        String reason() {
            return reason;
        }
    }

    /** One provider kind's way of listing the models a chat may pick on a connection. */
    interface Lister {

        /** The tool-capable models {@code llm} reports, in its order; any failure is thrown. */
        List<String> list(PipelineConfig.Llm llm) throws IOException, InterruptedException;
    }

    /**
     * The HTTP clients every adapter shares, bounded by {@link #DISCOVERY_TIMEOUT}.
     *
     * <p><b>A request carrying a credential never follows a redirect</b>: a 3xx would carry the
     * connection's key to wherever the {@code Location} points. A request with nothing but
     * {@link #CREDENTIAL_FREE} headers — Ollama's, which needs no key — follows one, so a proxy
     * moving a local server from http to https does not make it unreachable. The choice is made by
     * the headers a request actually carries, so a new header is kept from redirects until it is
     * named here.
     */
    static final class Discovery {

        /** The message a keyed listing called without a key fails with; it never quotes the value. */
        static final String KEY_REQUIRED = "a keyed listing needs a key; Providers.missing refuses a blank one first";

        /** The headers a request may carry and still follow a redirect, lower-cased. */
        static final Set<String> CREDENTIAL_FREE = Set.of("accept", "content-type");

        private final HttpClient keyed = HttpClient.newBuilder()
                .connectTimeout(DISCOVERY_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        private final HttpClient open = HttpClient.newBuilder()
                .connectTimeout(DISCOVERY_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        private final ObjectMapper json = new ObjectMapper();

        HttpRequest.Builder request(URI uri) {
            return HttpRequest.newBuilder(uri).timeout(DISCOVERY_TIMEOUT).header("Accept", "application/json");
        }

        /**
         * {@code url} as a URI a request can be built on, or {@link #INVALID_BASE_URL}: it must parse,
         * speak http or https, and name a host. The URI is checked on its own, before any header is
         * set, so a bad key can never be mistaken for a bad base URL.
         */
        static URI uri(String url) throws ListingException {
            try {
                URI uri = URI.create(url);
                String scheme = uri.getScheme();
                if (scheme == null
                        || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                        || uri.getHost() == null) {
                    throw new ListingException(INVALID_BASE_URL);
                }
                return uri;
            } catch (IllegalArgumentException e) {
                throw new ListingException(INVALID_BASE_URL);
            }
        }

        /**
         * {@code apiKey} as a credential header's value, or {@link #INVALID_KEY} when it holds anything
         * but printable ASCII. Checked before the header is set, so the HTTP client never refuses a
         * value — a refusal that would quote the whole key.
         *
         * <p>Whether a key is there at all is {@link Providers#missing}'s question, asked before any
         * lister is chosen, and worded {@link #NO_KEY} there. A blank key reaching this method is a
         * caller that skipped it, so it is refused with {@link #KEY_REQUIRED} — a fixed message — and
         * a request never carries {@code Bearer null} or an empty key.
         */
        static String key(String apiKey) throws ListingException {
            if (apiKey == null || apiKey.isBlank()) {
                throw new IllegalArgumentException(KEY_REQUIRED);
            }
            for (int i = 0; i < apiKey.length(); i++) {
                char c = apiKey.charAt(i);
                if (c < 0x20 || c > 0x7E) {
                    throw new ListingException(INVALID_KEY);
                }
            }
            return apiKey;
        }

        /** The body as JSON, or a {@link ListingException} for a redirect or any other non-2xx. */
        JsonNode send(HttpRequest request) throws IOException, InterruptedException {
            HttpClient http = carriesNoCredential(request) ? open : keyed;
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status / 100 == 3) {
                throw new ListingException(REDIRECTED);
            }
            if (status / 100 != 2) {
                throw new ListingException("the model list answered HTTP " + status);
            }
            return json.readTree(response.body());
        }

        private static boolean carriesNoCredential(HttpRequest request) {
            return request.headers().map().keySet().stream()
                    .allMatch(name -> CREDENTIAL_FREE.contains(name.toLowerCase(Locale.ROOT)));
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
