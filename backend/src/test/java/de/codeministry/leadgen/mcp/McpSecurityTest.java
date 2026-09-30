/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import de.codeministry.leadgen.Databases;
import de.codeministry.leadgen.config.ConfigFixtures;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code /mcp} follows {@code security.auth} (spec 023, ISC-487): open under {@code none} as every
 * endpoint is, and under {@code oidc} a 401 without a token, a 401 for a token the decoder refuses
 * and the ten tools for one it accepts. The protected resource metadata answers without a token and
 * names the MCP endpoint and the issuer, and a 401 on {@code /mcp} points at it.
 *
 * <p>The decoder is replaced, as in {@code SecurityConfigTest}: it is the same bean every endpoint
 * uses, so the issuer and audience checks it carries are the ones pinned there.
 */
@Testcontainers
class McpSecurityTest {

    private static final String ISSUER = "https://auth.example/realms/leadgen";

    private static final String RESOURCE = "https://leadgen.example.invalid/mcp";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = Databases.postgres();

    private static final Path OIDC_DIR = oidcConfig("");

    private static final Path OIDC_RESOURCE_DIR = oidcConfig(RESOURCE);

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    class WithAuthNone {

        @LocalServerPort
        private int port;

        @Test
        void refusesABrowserPageFromAnotherOrigin() {
            // DNS rebinding: a page on a name that resolves to this host posts from its own origin.
            assertThat(initialize(port, null, Map.of("Origin", "http://attacker.example"))
                            .statusCode())
                    .isEqualTo(403);
        }

        @Test
        void servesAClientWithoutAnOriginAndALocalBrowserTool() {
            assertThat(initialize(port, null).statusCode()).isEqualTo(200);
            assertThat(initialize(port, null, Map.of("Origin", "http://localhost:6274"))
                            .statusCode())
                    .isEqualTo(200);
        }

        @Test
        void listsTheToolsWithoutAToken() {
            assertThat(McpTestClient.connect("http://localhost:" + port).toolNames())
                    .hasSize(10);
        }
    }

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    class WithOidc {

        @DynamicPropertySource
        static void configuration(DynamicPropertyRegistry registry) {
            registry.add("leadgen.config-dir", OIDC_DIR::toString);
        }

        @MockitoBean
        private JwtDecoder decoder;

        @LocalServerPort
        private int port;

        @Test
        void refusesAClientWithoutATokenAndSaysWhereToAsk() {
            var response = initialize(port, null);

            assertThat(response.statusCode()).isEqualTo(401);
            assertThat(response.headers().firstValue("WWW-Authenticate"))
                    .hasValueSatisfying(value -> assertThat(value)
                            .contains(
                                    "resource_metadata=\"http://localhost:%d/.well-known/oauth-protected-resource/mcp\""
                                            .formatted(port)));
        }

        @Test
        void refusesATokenTheDecoderDoesNotAccept() {
            when(decoder.decode(anyString())).thenThrow(new BadJwtException("signature"));

            assertThat(initialize(port, "forged").statusCode()).isEqualTo(401);
        }

        @Test
        void servesTheToolsForAVerifiedToken() {
            when(decoder.decode(anyString())).thenReturn(verified());

            assertThat(McpTestClient.connect("http://localhost:" + port, "good").toolNames())
                    .hasSize(10);
        }

        @Test
        void readsTheSchemeAndHostTheProxyForwarded() {
            var forwarded = Map.of("X-Forwarded-Proto", "https", "X-Forwarded-Host", "leadgen.example.invalid");

            var metadata = get(port, "/.well-known/oauth-protected-resource/mcp", forwarded);
            assertThat(metadata.path("resource").asString()).isEqualTo("https://leadgen.example.invalid/mcp");

            var refused = initialize(port, null, forwarded);
            assertThat(refused.statusCode()).isEqualTo(401);
            assertThat(refused.headers().firstValue("WWW-Authenticate"))
                    .hasValueSatisfying(value -> assertThat(value)
                            .contains("resource_metadata=\"https://leadgen.example.invalid"
                                    + "/.well-known/oauth-protected-resource/mcp\""));
        }

        /** A proxy on a port of its own: the port travels in X-Forwarded-Port, or in the forwarded Host. */
        @Test
        void keepsThePortTheClientUsed() {
            var viaPort = get(
                    port,
                    "/.well-known/oauth-protected-resource/mcp",
                    Map.of(
                            "X-Forwarded-Proto", "http",
                            "X-Forwarded-Host", "leadgen.example.invalid",
                            "X-Forwarded-Port", "4200"));
            assertThat(viaPort.path("resource").asString()).isEqualTo("http://leadgen.example.invalid:4200/mcp");

            // What the dev server's proxy sends with xfwd: the Host with its port, and the port again.
            var viaHost = get(
                    port,
                    "/.well-known/oauth-protected-resource/mcp",
                    Map.of(
                            "X-Forwarded-Proto", "http",
                            "X-Forwarded-Host", "localhost:4200",
                            "X-Forwarded-Port", "4200"));
            assertThat(viaHost.path("resource").asString()).isEqualTo("http://localhost:4200/mcp");
        }

        /** HSTS is the TLS proxy's decision, not this process's, even when it now knows it sits behind one. */
        @Test
        void sendsNoStrictTransportSecurityBehindTls() {
            when(decoder.decode(anyString())).thenReturn(verified());
            var response = initialize(port, "good", Map.of("X-Forwarded-Proto", "https"));

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("Strict-Transport-Security"))
                    .isEmpty();
        }

        /** Under oidc the token is the guard: a browser-based client on any origin is served with one. */
        @Test
        void servesABrowserClientFromAnyOriginWithAToken() {
            when(decoder.decode(anyString())).thenReturn(verified());

            assertThat(initialize(port, "good", Map.of("Origin", "https://tools.example.invalid"))
                            .statusCode())
                    .isEqualTo(200);
        }

        @Test
        void describesTheMcpEndpointWithoutAToken() {
            var metadata = get(port, "/.well-known/oauth-protected-resource/mcp");

            assertThat(metadata.path("resource").asString()).isEqualTo("http://localhost:%d/mcp".formatted(port));
            assertThat(metadata.path("authorization_servers").path(0).asString())
                    .isEqualTo(ISSUER);
            assertThat(metadata.path("authorization_servers")).hasSize(1);
            assertThat(metadata.path("bearer_methods_supported").path(0).asString())
                    .isEqualTo("header");
            assertThat(metadata.path("tls_client_certificate_bound_access_tokens")
                            .asBoolean(true))
                    .isFalse();
        }
    }

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    class WithOidcAndAConfiguredResource {

        @DynamicPropertySource
        static void configuration(DynamicPropertyRegistry registry) {
            registry.add("leadgen.config-dir", OIDC_RESOURCE_DIR::toString);
        }

        @MockitoBean
        private JwtDecoder decoder;

        @LocalServerPort
        private int port;

        /**
         * The configured resource is the MCP endpoint's, so only its path-suffixed metadata names it;
         * the bare document describes the whole server, whose own 401s point there (RFC 9728 § 3.3).
         */
        @Test
        void namesTheConfiguredResourceForTheMcpEndpointOnly() {
            var mcp = get(port, "/.well-known/oauth-protected-resource/mcp");
            assertThat(mcp.path("resource").asString()).isEqualTo(RESOURCE);
            assertThat(mcp.path("authorization_servers").path(0).asString()).isEqualTo(ISSUER);

            var server = get(port, "/.well-known/oauth-protected-resource");
            assertThat(server.path("resource").asString()).isEqualTo("http://localhost:" + port);
            assertThat(server.path("authorization_servers").path(0).asString()).isEqualTo(ISSUER);
        }

        /**
         * The MCP endpoint by its path, exactly: a client compares the resource with the URL it derived
         * (RFC 9728 § 3.3), so a trailing slash or a deeper path keeps its own derived resource.
         */
        @Test
        void matchesTheMcpPathExactly() {
            assertThat(get(port, "/.well-known/oauth-protected-resource/mcp/")
                            .path("resource")
                            .asString())
                    .isEqualTo("http://localhost:" + port + "/mcp/");
            assertThat(get(port, "/.well-known/oauth-protected-resource/x/mcp")
                            .path("resource")
                            .asString())
                    .isEqualTo("http://localhost:" + port + "/x/mcp");
        }

        @Test
        void pointsA401AtTheConfiguredResourcesMetadata() {
            var response = initialize(port, null);

            assertThat(response.statusCode()).isEqualTo(401);
            assertThat(response.headers().firstValue("WWW-Authenticate"))
                    .hasValueSatisfying(
                            value -> assertThat(value)
                                    .contains(
                                            "resource_metadata=\"https://leadgen.example.invalid/.well-known/oauth-protected-resource/mcp\""));
        }

        /**
         * The 401 and the metadata agree on what the MCP endpoint is: {@code /mcp/} is not it, so its
         * 401 points at the whole server's document rather than at one whose resource it would reject.
         */
        @Test
        void pointsA401ElsewhereThanTheMcpPathAtTheServersMetadata() {
            var response = send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/mcp/"))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build());

            assertThat(response.statusCode()).isEqualTo(401);
            assertThat(response.headers().firstValue("WWW-Authenticate"))
                    .hasValueSatisfying(value -> assertThat(value)
                            .contains("resource_metadata=\"http://localhost:%d/.well-known/oauth-protected-resource\""
                                    .formatted(port)));
        }
    }

    private static HttpResponse<String> initialize(int port, String token) {
        return initialize(port, token, Map.of());
    }

    private static HttpResponse<String> initialize(int port, String token, Map<String, String> headers) {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/mcp"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
                          "protocolVersion":"2025-06-18","capabilities":{},
                          "clientInfo":{"name":"leadgen-test","version":"0"}}}"""));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        headers.forEach(request::header);
        return send(request.build());
    }

    private static JsonNode get(int port, String path) {
        return get(port, path, Map.of());
    }

    private static JsonNode get(int port, String path, Map<String, String> headers) {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .GET();
        headers.forEach(request::header);
        var response = send(request.build());
        assertThat(response.statusCode()).as(path).isEqualTo(200);
        return JSON.readTree(response.body());
    }

    private static HttpResponse<String> send(HttpRequest request) {
        try {
            return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static Jwt verified() {
        return Jwt.withTokenValue("good")
                .header("alg", "RS256")
                .claim("sub", "someone")
                .claim("iss", ISSUER)
                .issuedAt(Instant.now().minusSeconds(60))
                .expiresAt(Instant.now().plusSeconds(600))
                .build();
    }

    /** The shipped files under `oidc` with an issuer nobody calls, and the resource if one is given. */
    private static Path oidcConfig(String resource) {
        try {
            Path dir = Files.createTempDirectory("leadgen-mcp-oidc");
            dir.toFile().deleteOnExit();
            ConfigFixtures.materialize(dir);
            Path pipeline = dir.resolve("pipeline.yaml");
            String content = Files.readString(pipeline);
            String rewritten = content.replace("auth: ${AUTH_MODE:none}", "auth: oidc")
                    .replace("issuer: ${OIDC_ISSUER:}", "issuer: " + ISSUER)
                    .replace("resource: ${OIDC_RESOURCE:}", "resource: " + resource);
            if (!rewritten.contains("auth: oidc") || !rewritten.contains("resource: " + resource)) {
                throw new IllegalStateException(
                        "the shipped pipeline.yaml no longer spells the keys this test rewrites");
            }
            Files.writeString(pipeline, rewritten);
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
