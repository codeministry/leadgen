/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.mcp;

import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.transport.DefaultServerTransportSecurityValidator;
import java.util.List;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerStreamableHttpProperties;
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStreamableServerTransportProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/**
 * The Streamable HTTP transport, as Spring AI's auto-configuration builds it, plus the Origin check
 * the MCP transport spec requires against DNS rebinding.
 *
 * <p>A page on a name the attacker points at this machine posts to {@code /mcp} as a same-origin
 * request, and under {@code security.auth: none} nothing else stands in its way. The browser always
 * names that page's origin in {@code Origin}; an MCP client that is not a browser sends none, and is
 * served. So a request that carries an {@code Origin} is served only from a local browser tool, and
 * everything else is refused with 403 before any tool runs. The Host header is not checked: a
 * deployment reaches this process under whatever name its proxy gives it, and the Origin check alone
 * closes the rebinding path.
 */
@Configuration
class McpTransportConfig {

    /** Browser-based MCP tools on this machine, on any port (an inspector, a local dashboard). */
    static final List<String> LOCAL_ORIGINS = List.of(
            "http://localhost:*",
            "https://localhost:*",
            "http://127.0.0.1:*",
            "https://127.0.0.1:*",
            "http://[::1]:*",
            "https://[::1]:*");

    @Bean
    WebMvcStreamableServerTransportProvider webMvcStreamableServerTransportProvider(
            @Qualifier("mcpServerJsonMapper") JsonMapper jsonMapper, McpServerStreamableHttpProperties properties) {
        return WebMvcStreamableServerTransportProvider.builder()
                .jsonMapper(new JacksonMcpJsonMapper(jsonMapper))
                .mcpEndpoint(properties.getMcpEndpoint())
                .keepAliveInterval(properties.getKeepAliveInterval())
                .disallowDelete(properties.isDisallowDelete())
                .securityValidator(DefaultServerTransportSecurityValidator.builder()
                        .allowedOrigins(LOCAL_ORIGINS)
                        .build())
                .build();
    }
}
