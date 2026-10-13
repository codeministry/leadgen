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

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import de.codeministry.leadgen.chat.catalog.ChatModelCatalog;
import de.codeministry.leadgen.chat.catalog.ChatModelsView;
import de.codeministry.leadgen.chat.catalog.ConnectionView;
import de.codeministry.leadgen.chat.catalog.ModelPrice;
import de.codeministry.leadgen.chat.catalog.ModelView;
import de.codeministry.leadgen.config.ConfigRegistry;
import de.codeministry.leadgen.config.ConfigSnapshot;
import de.codeministry.leadgen.config.model.PipelineConfig;
import de.codeministry.leadgen.llm.ChatModels;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;

/**
 * ISC-505: the chat lists, per configured connection, the models that connection reports and that
 * can call tools — read from the connection, never from a list in {@code .env}. A connection that
 * does not answer is listed with its reason and no models, and never takes the other one down.
 *
 * <p>Two WireMock servers stand in for the two connections: the primary speaks Ollama's native
 * API, the second the hosted kind's paged model list. Every model name is neutral.
 *
 * <p>The concurrent cases never coordinate by the clock: a {@link GatedOllama} holds every model
 * list until the test releases it and says when a request has arrived, each caller thread reads its
 * own snapshot object, and the registry mock is stubbed once and never again while one runs.
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

    /** How long a test waits on something that should already be happening; only a hang reaches it. */
    private static final Duration HANG = Duration.ofSeconds(10);

    /** The snapshot a caller thread holds, when it is not the registry's current one. */
    private static final ThreadLocal<ConfigSnapshot> PINNED = new ThreadLocal<>();

    private final ConfigRegistry registry = Mockito.mock(ConfigRegistry.class);
    private final AtomicReference<ConfigSnapshot> current = new AtomicReference<>();

    @AfterAll
    static void stop() {
        PRIMARY.stop();
        SECOND.stop();
    }

    @BeforeEach
    void reset() {
        // Stubbed once: a reload swaps what `current` holds, never the stubbing a caller thread is reading.
        given(registry.snapshot()).willAnswer(_ -> {
            ConfigSnapshot pinned = PINNED.get();
            return pinned != null ? pinned : current.get();
        });
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
        assertThat(view.defaultModelName()).isEqualTo("model-a");
        ConnectionView primary = view.connections().get(0);
        assertThat(primary.kind()).isEqualTo(ChatModels.OLLAMA);
        assertThat(primary.billed()).isFalse();
        assertThat(primary.reachable()).isTrue();
        assertThat(primary.reason()).isNull();
        // Three reported, the one without `tools` among its capabilities is absent.
        assertThat(primary.models())
                .containsExactly(
                        new ModelView("model-a", true, true, null), new ModelView("model-b", false, true, null));

        ConnectionView second = view.connections().get(1);
        assertThat(second.kind()).isEqualTo(ChatModels.ANTHROPIC);
        assertThat(second.billed()).isTrue();
        assertThat(second.reachable()).isTrue();
        // Two pages, one model each; the default lives on the primary only. No price table: unpriced.
        assertThat(second.models())
                .containsExactly(
                        new ModelView("model-x", false, false, null), new ModelView("model-y", false, false, null));

        // Five reported in all, four listed.
        PRIMARY.verify(1, getRequestedFor(urlPathEqualTo("/api/tags")));
        PRIMARY.verify(3, postRequestedFor(urlPathEqualTo("/api/show")));
        SECOND.verify(getRequestedFor(urlPathEqualTo("/v1/models"))
                .withHeader("x-api-key", equalTo("second-key"))
                .withHeader("anthropic-version", matching(".+")));
        SECOND.verify(getRequestedFor(urlPathEqualTo("/v1/models")).withQueryParam("after_id", equalTo("model-x")));
    }

    /**
     * ISC-517: every listed model names what it costs. The unbilled primary's models are free, even
     * one the table prices; on the billed second the priced model carries both halves as decimal
     * strings and the other is unpriced; an entry for a model neither connection lists is nowhere.
     */
    @Test
    void eachListedModelIsFreePricedOrUnpriced() {
        use(
                ollamaPrimary(),
                hostedSecond(),
                List.of(
                        price("PRIMARY", "model-a", "9", "9"),
                        price("SECOND", "model-x", "3", "15.50"),
                        price("SECOND", "model-gone", "1", "2")));

        ChatModelsView view = new ChatModelCatalog(registry).models();

        assertThat(view.connections().get(0).models())
                .containsExactly(
                        new ModelView("model-a", true, true, null), new ModelView("model-b", false, true, null));
        assertThat(view.connections().get(1).models())
                .containsExactly(
                        new ModelView("model-x", false, false, new ModelPrice("3", "15.5")),
                        new ModelView("model-y", false, false, null));
        assertThat(view.connections())
                .flatExtracting(ConnectionView::models)
                .extracting(ModelView::name)
                .doesNotContain("model-gone");
    }

    /**
     * The default model's price rides along beside its name, by the lookup a turn uses: priced on a
     * billed primary, null on a billed primary nobody priced, and null on an unbilled one even when
     * the table names it — a free model has no price to show.
     */
    @Test
    void theDefaultModelsPriceIsTheOneATurnOnThePrimaryIsCostedAt() {
        use(hostedPrimary(), null, List.of(price("PRIMARY", "model-x", "3", "15.50")));
        assertThat(new ChatModelCatalog(registry).models().defaultModelPrice())
                .as("billed and priced")
                .isEqualTo(new ModelPrice("3", "15.5"));

        use(hostedPrimary(), null, List.of(price("PRIMARY", "model-y", "1", "2")));
        assertThat(new ChatModelCatalog(registry).models().defaultModelPrice())
                .as("billed, the default unpriced")
                .isNull();

        use(ollamaPrimary(), null, List.of(price("PRIMARY", "model-a", "9", "9")));
        assertThat(new ChatModelCatalog(registry).models().defaultModelPrice())
                .as("unbilled")
                .isNull();
    }

    /** ISC-517: a price is read from the configuration current when the list is asked for. */
    @Test
    void aPriceChangedByAReloadShowsOnTheNextList() {
        use(ollamaPrimary(), hostedSecond(), List.of(price("SECOND", "model-y", "1", "2")));
        var catalog = new ChatModelCatalog(registry);
        assertThat(catalog.models().connections().get(1).models().get(1).price())
                .isEqualTo(new ModelPrice("1", "2"));

        use(ollamaPrimary(), hostedSecond(), List.of(price("SECOND", "model-y", "0.25", "1.125")));

        assertThat(catalog.models().connections().get(1).models().get(1).price())
                .isEqualTo(new ModelPrice("0.25", "1.125"));
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
        assertThat(second.reason()).isEqualTo("the model list answered HTTP 500");
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

    /**
     * Every kind but Ollama needs a key before a chat model is built on it, so a keyless one could
     * list models no turn can use. It is listed unreachable with the fixed reason, and never asked.
     */
    @Test
    void aKeylessHostedConnectionIsUnreachableAndNeverAsked() {
        SECOND.resetAll();
        SECOND.stubFor(get(urlPathEqualTo("/v1/models")).willReturn(okJson("{\"data\":[{\"id\":\"model-p\"}]}")));
        var keyless = new PipelineConfig.ChatConnection(
                ChatModels.OPENAI_COMPATIBLE, SECOND.baseUrl() + "/v1", null, null, null, null);
        use(ollamaPrimary(), keyless);

        ConnectionView second =
                new ChatModelCatalog(registry).models().connections().get(1);

        assertThat(second.reachable()).isFalse();
        assertThat(second.reason()).isEqualTo("no API key is configured for this connection");
        assertThat(second.models()).isEmpty();
        assertThat(SECOND.getAllServeEvents()).isEmpty();
    }

    /** A 3xx is not followed, because the request carries the connection's key; its target is never asked. */
    @Test
    void aRedirectIsUnreachableAndItsTargetNeverAsked() {
        SECOND.resetAll();
        SECOND.stubFor(get(urlPathEqualTo("/v1/models"))
                .willReturn(aResponse().withStatus(302).withHeader("Location", SECOND.baseUrl() + "/elsewhere")));
        SECOND.stubFor(get(urlPathEqualTo("/elsewhere")).willReturn(okJson("{\"data\":[{\"id\":\"model-x\"}]}")));
        use(ollamaPrimary(), hostedSecond());

        ConnectionView second =
                new ChatModelCatalog(registry).models().connections().get(1);

        assertThat(second.reachable()).isFalse();
        assertThat(second.reason()).isEqualTo("the model list answered with a redirect");
        SECOND.verify(0, getRequestedFor(urlPathEqualTo("/elsewhere")));
    }

    /** A body that is not JSON is "could not be read", never a parser's snippet of what it said. */
    @Test
    void anUnparseableBodyIsUnreadableWithoutQuotingIt() {
        SECOND.resetAll();
        SECOND.stubFor(get(urlPathEqualTo("/v1/models"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("internal-detail-from-the-body {not json")));
        use(ollamaPrimary(), hostedSecond());

        ConnectionView second =
                new ChatModelCatalog(registry).models().connections().get(1);

        assertThat(second.reachable()).isFalse();
        assertThat(second.reason())
                .isEqualTo("the model list could not be read")
                .doesNotContain("internal-detail");
    }

    /**
     * No lock is held across the network, and two callers missing together wait on one discovery
     * rather than each starting their own: the held primary is asked once.
     */
    @Test
    void twoConcurrentMissesShareOneDiscovery() throws Exception {
        try (var gate = new GatedOllama()) {
            use(ollamaAt(gate.baseUrl("/a")), null);
            var catalog = new ChatModelCatalog(registry);

            var first = new Caller(catalog, null);
            gate.awaitArrival("/a");
            var second = new Caller(catalog, null);
            second.awaitParked();
            gate.release();

            // Equal rather than the same object: the prices are joined on per call (ISC-517), so the
            // shared discovery shows in the one listing the primary was asked for.
            assertThat(first.view()).isEqualTo(second.view());
            assertThat(gate.listings("/a")).isEqualTo(1);
        }
    }

    /**
     * A discovery in flight holds nobody else up: after a reload, a caller under the new
     * configuration is answered while the old one is still held at its primary.
     */
    @Test
    void aSlowDiscoveryDoesNotHoldUpACallerUnderANewConfiguration() throws Exception {
        try (var gate = new GatedOllama()) {
            use(ollamaAt(gate.baseUrl("/a")), null);
            var catalog = new ChatModelCatalog(registry);
            var slow = new Caller(catalog, null);
            gate.awaitArrival("/a");

            // The reload: the primary is now the hosted stub, which answers at once.
            use(hostedPrimary(), null);
            ChatModelsView fast = catalog.models();

            assertThat(fast.connections().get(0).models())
                    .extracting(ModelView::name)
                    .containsExactly("model-x", "model-y");
            assertThat(slow.done()).isFalse();
            gate.release();
            slow.view();

            // The slow list belongs to the old configuration and is not published over the new one.
            assertThat(catalog.contains("PRIMARY", "model-x")).isTrue();
        }
    }

    /**
     * A caller still holding the snapshot from before a reload waits on that snapshot's discovery
     * and never displaces the new one's, so a caller under the new snapshot does not start a second.
     */
    @Test
    void anOldSnapshotCallerDoesNotDuplicateTheNewSnapshotsDiscovery() throws Exception {
        try (var gate = new GatedOllama()) {
            ConfigSnapshot before = snapshot(ollamaAt(gate.baseUrl("/a")), null);
            current.set(before);
            var catalog = new ChatModelCatalog(registry);
            var firstBefore = new Caller(catalog, null);
            gate.awaitArrival("/a");

            use(ollamaAt(gate.baseUrl("/b")), null);
            var firstAfter = new Caller(catalog, null);
            gate.awaitArrival("/b");

            var lateBefore = new Caller(catalog, before);
            lateBefore.awaitParked();
            var secondAfter = new Caller(catalog, null);
            secondAfter.awaitParked();
            gate.release();

            // Equal, with one listing per snapshot below: the prices are joined on per call (ISC-517).
            assertThat(secondAfter.view()).isEqualTo(firstAfter.view());
            assertThat(lateBefore.view()).isEqualTo(firstBefore.view());
            assertThat(gate.listings("/b")).isEqualTo(1);
            assertThat(gate.listings("/a")).isEqualTo(1);
            assertThat(catalog.contains("PRIMARY", "model-a")).isTrue();
        }
    }

    /**
     * A caller interrupted mid-discovery gets the interrupted view for itself, with its interrupt
     * flag still set; nobody else does. The caller waiting on it asks again, and the view the cache
     * holds is that second, uninterrupted one.
     */
    @Test
    void anInterruptedDiscoveryIsNeitherCachedNorHandedToItsWaiters() throws Exception {
        try (var gate = new GatedOllama()) {
            use(ollamaAt(gate.baseUrl("/a")), null);
            var catalog = new ChatModelCatalog(registry);
            var interrupted = new Caller(catalog, null);
            gate.awaitArrival("/a");
            var waiter = new Caller(catalog, null);
            waiter.awaitParked();

            interrupted.thread.interrupt();

            ConnectionView own = interrupted.view().connections().get(0);
            assertThat(own.reachable()).isFalse();
            assertThat(own.reason()).isEqualTo("the listing was interrupted");
            assertThat(interrupted.interruptedAfter).isTrue();
            assertThat(catalog.contains("PRIMARY", "model-a")).isFalse();

            // The waiter's own discovery: a second request to the primary, held like the first.
            gate.awaitArrival("/a");
            assertThat(waiter.done()).isFalse();
            gate.release();

            ConnectionView shared = waiter.view().connections().get(0);
            assertThat(shared.reachable()).isTrue();
            assertThat(shared.models()).extracting(ModelView::name).containsExactly("model-a");
            assertThat(catalog.contains("PRIMARY", "model-a")).isTrue();
            catalog.models();
            assertThat(gate.listings("/a")).isEqualTo(2);
        }
    }

    /**
     * A key with a control character in it cannot go into a header. That is its own reason, decided
     * before any request is built, and no part of the key reaches the log — the HTTP client's own
     * refusal would have quoted the whole header value.
     */
    @Test
    void anApiKeyWithAControlCharacterIsInvalidAndNeverLogged() {
        var anthropic = new PipelineConfig.Llm(
                ChatModels.ANTHROPIC,
                SECOND.baseUrl(),
                "primary-leak-canary\n",
                null,
                false,
                new PipelineConfig.Llm.Models(null, "model-z", null, null, null, null, null, "model-x"),
                null);
        var compatible = new PipelineConfig.ChatConnection(
                ChatModels.OPENAI_COMPATIBLE, SECOND.baseUrl() + "/v1", "second-leak-canary\r", null, null, null);
        use(anthropic, compatible);
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> logged = new ListAppender<>();
        logged.start();
        root.addAppender(logged);
        ChatModelsView view;
        try {
            view = new ChatModelCatalog(registry).models();
        } finally {
            root.detachAppender(logged);
        }

        assertThat(view.connections())
                .extracting(ConnectionView::reason)
                .containsExactly(
                        "the API key of this connection is not valid", "the API key of this connection is not valid");
        assertThat(SECOND.getAllServeEvents()).isEmpty();
        assertThat(logged.list).isNotEmpty();
        for (ILoggingEvent event : logged.list) {
            assertThat(event.getFormattedMessage()).doesNotContain("leak-canary");
            if (event.getThrowableProxy() != null) {
                assertThat(ThrowableProxyUtil.asString(event.getThrowableProxy()))
                        .doesNotContain("leak-canary");
            }
        }
    }

    /**
     * The reason a redirect is not followed is the key it would carry along. Ollama's listing carries
     * none, so behind a proxy that moves it, it is followed and listed; a keyed listing still is not.
     */
    @Test
    void aKeylessListingFollowsARedirectAndAKeyedOneDoesNot() {
        PRIMARY.stubFor(get(urlPathEqualTo("/api/tags"))
                .willReturn(aResponse().withStatus(301).withHeader("Location", PRIMARY.baseUrl() + "/moved/api/tags")));
        PRIMARY.stubFor(
                get(urlPathEqualTo("/moved/api/tags")).willReturn(okJson("{\"models\":[{\"name\":\"model-a\"}]}")));
        SECOND.resetAll();
        SECOND.stubFor(get(urlPathEqualTo("/v1/models"))
                .willReturn(aResponse().withStatus(301).withHeader("Location", SECOND.baseUrl() + "/elsewhere")));
        SECOND.stubFor(get(urlPathEqualTo("/elsewhere")).willReturn(okJson("{\"data\":[{\"id\":\"model-x\"}]}")));
        use(ollamaPrimary(), hostedSecond());

        ChatModelsView view = new ChatModelCatalog(registry).models();

        ConnectionView primary = view.connections().get(0);
        assertThat(primary.reachable()).isTrue();
        assertThat(primary.models()).extracting(ModelView::name).containsExactly("model-a");
        ConnectionView second = view.connections().get(1);
        assertThat(second.reachable()).isFalse();
        assertThat(second.reason()).isEqualTo("the model list answered with a redirect");
        SECOND.verify(0, getRequestedFor(urlPathEqualTo("/elsewhere")));
    }

    /** A connection without a provider kind cannot be listed, and nobody is asked. */
    @Test
    void aConnectionWithoutAProviderKindIsUnlistable() {
        var kindless = new PipelineConfig.Llm(
                null,
                PRIMARY.baseUrl(),
                null,
                null,
                false,
                new PipelineConfig.Llm.Models(null, "model-z", null, null, null, null, null, "model-a"),
                null);
        use(kindless, null);

        ConnectionView primary =
                new ChatModelCatalog(registry).models().connections().get(0);

        assertThat(primary.reachable()).isFalse();
        assertThat(primary.reason()).isEqualTo("this provider kind cannot list its models");
        assertThat(PRIMARY.getAllServeEvents()).isEmpty();
    }

    /**
     * A caller waiting on someone else's discovery can be interrupted: it gets the interrupted view
     * for itself, with its flag still set, and the discovery it waited on goes on and is cached.
     */
    @Test
    void anInterruptedWaiterGetsItsOwnInterruptedViewAndTheDiscoveryGoesOn() throws Exception {
        try (var gate = new GatedOllama()) {
            use(ollamaAt(gate.baseUrl("/a")), null);
            var catalog = new ChatModelCatalog(registry);
            var running = new Caller(catalog, null);
            gate.awaitArrival("/a");
            var waiter = new Caller(catalog, null);
            waiter.awaitParked();

            waiter.thread.interrupt();

            ConnectionView own = waiter.view().connections().get(0);
            assertThat(own.reachable()).isFalse();
            assertThat(own.reason()).isEqualTo("the listing was interrupted");
            assertThat(waiter.interruptedAfter).isTrue();
            assertThat(running.done()).isFalse();
            assertThat(catalog.contains("PRIMARY", "model-a")).isFalse();

            gate.release();

            assertThat(running.view().connections().get(0).reachable()).isTrue();
            assertThat(catalog.contains("PRIMARY", "model-a")).isTrue();
            assertThat(gate.listings("/a")).isEqualTo(1);
        }
    }

    /**
     * A waiter whose shared discovery was interrupted asks again under the configuration current
     * then, not the one it first read: a reload in between is what it lists.
     */
    @Test
    void aWaiterAskingAgainReadsTheCurrentConfiguration() throws Exception {
        try (var gate = new GatedOllama()) {
            use(ollamaAt(gate.baseUrl("/a")), null);
            var catalog = new ChatModelCatalog(registry);
            var interrupted = new Caller(catalog, null);
            gate.awaitArrival("/a");
            var waiter = new Caller(catalog, null);
            waiter.awaitParked();

            use(ollamaAt(gate.baseUrl("/b")), null);
            interrupted.thread.interrupt();
            interrupted.view();

            gate.awaitArrival("/b");
            gate.release();

            assertThat(waiter.view().connections().get(0).reachable()).isTrue();
            assertThat(gate.listings("/a")).isEqualTo(1);
            assertThat(gate.listings("/b")).isEqualTo(1);
            assertThat(catalog.contains("PRIMARY", "model-a")).isTrue();
        }
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

    private static PipelineConfig.Llm ollamaAt(String baseUrl) {
        return new PipelineConfig.Llm(
                ChatModels.OLLAMA,
                baseUrl,
                null,
                null,
                false,
                new PipelineConfig.Llm.Models(null, "model-z", null, null, null, null, null, "model-a"),
                null);
    }

    private static PipelineConfig.Llm hostedPrimary() {
        return new PipelineConfig.Llm(
                ChatModels.ANTHROPIC,
                SECOND.baseUrl(),
                "primary-key",
                null,
                false,
                new PipelineConfig.Llm.Models(null, "model-z", null, null, null, null, null, "model-x"),
                null);
    }

    private static PipelineConfig.ChatConnection hostedSecond() {
        return new PipelineConfig.ChatConnection(
                ChatModels.ANTHROPIC, SECOND.baseUrl(), "second-key", null, null, null);
    }

    /** A fresh snapshot object, as a reload would hand out, served from now on. */
    private void use(PipelineConfig.Llm llm, PipelineConfig.ChatConnection second) {
        use(llm, second, List.of());
    }

    /** The same, with {@code prices} as the chat's price table. */
    private void use(
            PipelineConfig.Llm llm, PipelineConfig.ChatConnection second, List<PipelineConfig.ChatPrice> prices) {
        current.set(snapshot(llm, second, prices));
    }

    private static ConfigSnapshot snapshot(PipelineConfig.Llm llm, PipelineConfig.ChatConnection second) {
        return snapshot(llm, second, List.of());
    }

    private static ConfigSnapshot snapshot(
            PipelineConfig.Llm llm, PipelineConfig.ChatConnection second, List<PipelineConfig.ChatPrice> prices) {
        var pipeline = Mockito.mock(PipelineConfig.class);
        given(pipeline.llm()).willReturn(llm);
        given(pipeline.chat()).willReturn(new PipelineConfig.Chat(null, null, null, second, prices));
        return new ConfigSnapshot(pipeline, null, null, null, null, null);
    }

    private static PipelineConfig.ChatPrice price(String connection, String model, String input, String output) {
        return new PipelineConfig.ChatPrice(connection, model, new BigDecimal(input), new BigDecimal(output));
    }

    /**
     * One caller of {@link ChatModelCatalog#models()} on a thread of its own, reading {@code pinned}
     * as its snapshot when one is given and the registry's current one otherwise.
     */
    private static final class Caller {

        private final CompletableFuture<ChatModelsView> view = new CompletableFuture<>();
        private final Thread thread;
        private volatile boolean interruptedAfter;

        Caller(ChatModelCatalog catalog, ConfigSnapshot pinned) {
            thread = Thread.ofPlatform().daemon().start(() -> {
                if (pinned != null) {
                    PINNED.set(pinned);
                }
                try {
                    ChatModelsView answer = catalog.models();
                    interruptedAfter = Thread.currentThread().isInterrupted();
                    view.complete(answer);
                } catch (Throwable e) {
                    view.completeExceptionally(e);
                }
            });
        }

        ChatModelsView view() throws Exception {
            return view.get(HANG.toSeconds(), TimeUnit.SECONDS);
        }

        boolean done() {
            return view.isDone();
        }

        /**
         * Returns once the caller is parked on a discovery someone else is running — waiting, inside
         * the catalog's {@code await}, on the in-flight future's {@code get} — and fails if it answered
         * instead. A caller blocked anywhere else (its own discovery's HTTP request, a class being
         * loaded) does not count. Watches the thread's state and stack, never the clock; {@link #HANG}
         * only bounds a hang.
         */
        void awaitParked() {
            long giveUp = System.nanoTime() + HANG.toNanos();
            while (!parkedOnSharedDiscovery()) {
                assertThat(view.isDone())
                        .as("the caller answered instead of waiting")
                        .isFalse();
                assertThat(System.nanoTime())
                        .as("the caller never parked on the shared discovery")
                        .isLessThan(giveUp);
                Thread.onSpinWait();
            }
        }

        /**
         * Whether the thread is waiting and one snapshot of its stack has it parked — the top frame a
         * {@code park} — inside {@code CompletableFuture.get} called from {@code ChatModelCatalog.await}:
         * the future another caller completes. The park on top ties the snapshot to that very wait, so
         * a thread that waited elsewhere a moment before or after does not pass; once it is in that
         * frame it has joined the shared discovery.
         */
        private boolean parkedOnSharedDiscovery() {
            Thread.State state = thread.getState();
            if (state != Thread.State.WAITING && state != Thread.State.TIMED_WAITING) {
                return false;
            }
            StackTraceElement[] stack = thread.getStackTrace();
            if (stack.length == 0 || !stack[0].getMethodName().equals("park")) {
                return false;
            }
            for (int i = 0; i < stack.length; i++) {
                if (stack[i].getClassName().equals(ChatModelCatalog.class.getName())
                        && stack[i].getMethodName().equals("await")) {
                    // Every frame above the catalog's await is what it called into, so the future's
                    // get has to be among them; a park anywhere else is not this wait.
                    for (int j = 0; j < i; j++) {
                        if (stack[j].getClassName().equals(CompletableFuture.class.getName())
                                && (stack[j].getMethodName().equals("get")
                                        || stack[j].getMethodName().equals("waitingGet"))) {
                            return true;
                        }
                    }
                    return false;
                }
            }
            return false;
        }
    }

    /**
     * An Ollama server whose model lists ({@code <prefix>/api/tags}) are held until {@link #release()},
     * counted per base-URL prefix, and announced as they arrive, so a test knows a discovery is in
     * flight without waiting on the clock. Each lists {@code model-a}, which can call tools.
     */
    private static final class GatedOllama implements AutoCloseable {

        private final HttpServer server;
        private final CountDownLatch gate = new CountDownLatch(1);
        private final ConcurrentMap<String, Semaphore> arrived = new ConcurrentHashMap<>();
        private final ConcurrentMap<String, AtomicInteger> listings = new ConcurrentHashMap<>();

        GatedOllama() throws IOException {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            server.createContext("/", this::handle);
            server.start();
        }

        /** The base URL a primary on {@code prefix} is configured with, OpenAI-compatible path and all. */
        String baseUrl(String prefix) {
            return "http://" + server.getAddress().getHostString() + ":"
                    + server.getAddress().getPort() + prefix + "/v1/";
        }

        void awaitArrival(String prefix) throws InterruptedException {
            assertThat(arrived(prefix).tryAcquire(HANG.toSeconds(), TimeUnit.SECONDS))
                    .as("a model list request on %s", prefix)
                    .isTrue();
        }

        void release() {
            gate.countDown();
        }

        int listings(String prefix) {
            return listings.computeIfAbsent(prefix, _ -> new AtomicInteger()).get();
        }

        private Semaphore arrived(String prefix) {
            return arrived.computeIfAbsent(prefix, _ -> new Semaphore(0));
        }

        private void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            String prefix = path.substring(0, path.indexOf("/api/"));
            String body;
            if (path.endsWith("/api/tags")) {
                listings.computeIfAbsent(prefix, _ -> new AtomicInteger()).incrementAndGet();
                arrived(prefix).release();
                try {
                    gate.await(HANG.toSeconds(), TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                body = "{\"models\":[{\"name\":\"model-a\"}]}";
            } else {
                body = "{\"capabilities\":[\"completion\",\"tools\"]}";
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            try (OutputStream out = exchange.getResponseBody()) {
                exchange.sendResponseHeaders(200, bytes.length);
                out.write(bytes);
            } catch (IOException gone) {
                // The client stopped waiting — the interrupted caller's request.
            }
        }

        @Override
        public void close() {
            gate.countDown();
            server.stop(0);
        }
    }
}
