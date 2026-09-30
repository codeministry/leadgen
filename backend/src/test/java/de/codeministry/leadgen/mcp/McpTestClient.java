/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.mcp;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A minimal MCP client over Streamable HTTP, enough for tests: `initialize`, the `initialized`
 * notification, then JSON-RPC calls on the session the server handed out.
 *
 * <p>Plain {@code RestClient} rather than an MCP SDK client, so what the test sends is exactly what
 * a client on the wire sends, and nothing on the test classpath can paper over a transport problem.
 * Taken from codeministry-mcp's contract tests, which spoke to the same server shape.
 */
final class McpTestClient {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PROTOCOL = "2025-06-18";

    private final RestClient http;
    private final String sessionId;
    private int nextId = 2;

    private McpTestClient(RestClient http, String sessionId) {
        this.http = http;
        this.sessionId = sessionId;
    }

    static McpTestClient connect(String baseUrl) {
        return connect(baseUrl, null);
    }

    /** With a bearer token, for the `oidc` chain; null sends none. */
    static McpTestClient connect(String baseUrl, String token) {
        var builder = RestClient.builder().baseUrl(baseUrl + "/mcp");
        if (token != null) {
            builder.defaultHeader("Authorization", "Bearer " + token);
        }
        var http = builder.build();
        var response = http.post()
                .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
                          "protocolVersion":"%s","capabilities":{},
                          "clientInfo":{"name":"leadgen-test","version":"0"}}}""".formatted(PROTOCOL))
                .retrieve()
                .toEntity(String.class);
        var session = Objects.requireNonNull(
                response.getHeaders().getFirst("Mcp-Session-Id"), "initialize returned no Mcp-Session-Id");
        var client = new McpTestClient(http, session);
        client.send("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
        return client;
    }

    /** The names `tools/list` answers, in the server's order. */
    List<String> toolNames() {
        var names = new ArrayList<String>();
        tools().forEach(tool -> names.add(tool.path("name").asString()));
        return names;
    }

    /** The `tools` array of `tools/list`. */
    JsonNode tools() {
        return rpc("tools/list", "{}").path("tools");
    }

    /** `tools/call` with the given arguments as a JSON object literal; answers the `result`. */
    JsonNode callTool(String name, String argumentsJson) {
        return rpc("tools/call", "{\"name\":\"%s\",\"arguments\":%s}".formatted(name, argumentsJson));
    }

    /** The tool's structured answer: the first text content parsed as JSON. */
    JsonNode callToolJson(String name, String argumentsJson) {
        var result = callTool(name, argumentsJson);
        var text = result.path("content").path(0).path("text").asString();
        return JSON.readTree(text);
    }

    private JsonNode rpc(String method, String params) {
        var body =
                "{\"jsonrpc\":\"2.0\",\"id\":%d,\"method\":\"%s\",\"params\":%s}".formatted(nextId++, method, params);
        return JSON.readTree(dataFrame(send(body))).path("result");
    }

    private String send(String body) {
        var bytes = http.post()
                .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Mcp-Session-Id", sessionId)
                .header("MCP-Protocol-Version", PROTOCOL)
                .body(body)
                .retrieve()
                .body(byte[].class);
        return bytes == null ? "" : new String(bytes, StandardCharsets.UTF_8);
    }

    private static String dataFrame(String response) {
        if (response.stripLeading().startsWith("{")) {
            return response;
        }
        return response.lines()
                .filter(line -> line.startsWith("data:"))
                .reduce((first, second) -> second)
                .map(line -> line.substring("data:".length()))
                .orElseThrow(() -> new IllegalStateException("no data frame in response: " + response));
    }
}
