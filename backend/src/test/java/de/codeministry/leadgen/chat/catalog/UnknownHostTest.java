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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.withSettings;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import de.codeministry.leadgen.config.ConfigRegistry;
import de.codeministry.leadgen.config.ConfigSnapshot;
import de.codeministry.leadgen.config.model.PipelineConfig;
import de.codeministry.leadgen.llm.ChatModels;
import java.net.UnknownHostException;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;

/**
 * ISC-505: a host that does not resolve is "could not be reached", a fixed sentence; the name the
 * exception carries reaches neither the client nor the server log.
 *
 * <p>No lookup leaves the machine: the catalog's {@link ChatModelCatalog.Discovery} is replaced as
 * it is constructed, and its {@code send} fails the way the HTTP client does for a name nobody
 * resolves. Everything else — the request, the lister, the reason, the log — is the real code.
 */
class UnknownHostTest {

    private static final String HOST = "model-host-nobody-runs.invalid";

    @Test
    void anUnknownHostIsUnreachableWithoutNamingTheHost() throws Exception {
        var primary = new PipelineConfig.Llm(
                ChatModels.ANTHROPIC,
                "http://" + HOST,
                "primary-key",
                null,
                false,
                new PipelineConfig.Llm.Models(null, "model-z", null, null, null, null, null, "model-x"),
                null);
        var pipeline = Mockito.mock(PipelineConfig.class);
        given(pipeline.llm()).willReturn(primary);
        given(pipeline.chat()).willReturn(new PipelineConfig.Chat(null, null, null, null));
        var registry = Mockito.mock(ConfigRegistry.class);
        given(registry.snapshot()).willReturn(new ConfigSnapshot(pipeline, null, null, null, null, null));

        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> logged = new ListAppender<>();
        logged.start();
        root.addAppender(logged);
        ChatModelsView view;
        try (var discoveries = mockConstruction(
                ChatModelCatalog.Discovery.class,
                withSettings().defaultAnswer(CALLS_REAL_METHODS),
                (discovery, _) ->
                        doThrow(new UnknownHostException(HOST)).when(discovery).send(any()))) {
            view = new ChatModelCatalog(registry).models();
            assertThat(discoveries.constructed()).hasSize(1);
        } finally {
            root.detachAppender(logged);
        }

        ConnectionView connection = view.connections().getFirst();
        assertThat(connection.reachable()).isFalse();
        assertThat(connection.reason())
                .isEqualTo(ChatModelCatalog.UNREACHABLE)
                .doesNotContain("model-host")
                .doesNotContain("nobody-runs")
                .doesNotContain("invalid");
        assertThat(connection.models()).isEmpty();
        assertThat(logged.list).isNotEmpty();
        for (ILoggingEvent event : logged.list) {
            assertThat(event.getFormattedMessage()).doesNotContain("nobody-runs");
            if (event.getThrowableProxy() != null) {
                assertThat(ThrowableProxyUtil.asString(event.getThrowableProxy()))
                        .doesNotContain("nobody-runs");
            }
        }
    }
}
