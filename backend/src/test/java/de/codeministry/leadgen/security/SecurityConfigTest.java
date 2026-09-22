/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.security;

import de.codeministry.leadgen.Databases;
import de.codeministry.leadgen.config.ConfigFixtures;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * The two modes of {@code security.auth}, and the line between them.
 *
 * <p>The decoder is replaced rather than a realm stood up: what is under test is the shape
 * of the filter chain, not Nimbus's signature checking. The one thing a stub cannot show is
 * the discovery request against a real issuer, and that is said in the ISA rather than
 * pretended at here.
 */
@Testcontainers
class SecurityConfigTest {

    private static final String ISSUER = "https://auth.example/realms/leadgen";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = Databases.postgres();

    /**
     * A configuration directory whose `pipeline.yaml` says `oidc` and names an issuer.
     * Nobody calls that issuer, because the decoder that would is replaced.
     *
     * <p>Built in a static field rather than in the supplier: a `@DynamicPropertySource`
     * supplier may run more than once, and creating a directory in one produces a second
     * configuration nobody is looking at.
     */
    private static final Path OIDC_DIR = oidcConfig();

    private static Path oidcConfig() {
        try {
            Path dir = Files.createTempDirectory("leadgen-oidc");
            dir.toFile().deleteOnExit();
            ConfigFixtures.materialize(dir);
            Path pipeline = dir.resolve("pipeline.yaml");
            String content = Files.readString(pipeline);
            String rewritten = content.replace("auth: ${AUTH_MODE:none}", "auth: oidc")
                    .replace("issuer: ${OIDC_ISSUER:}", "issuer: " + ISSUER);
            if (rewritten.equals(content)) {
                throw new IllegalStateException(
                        "the shipped pipeline.yaml no longer spells the auth keys the way this test rewrites them");
            }
            Files.writeString(pipeline, rewritten);
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Nested
    @SpringBootTest
    @AutoConfigureMockMvc
    class WithAuthNone {

        @Autowired
        private MockMvcTester mvc;

        @Test
        void answersWithoutAnyToken() {
            // What shipped, and it has to keep shipping: `none` puts nothing in front of
            // the endpoints, and SERVER_ADDRESS is the guard.
            Assertions.assertThat(mvc.get().uri("/api/v1/status")).hasStatusOk();
        }
    }

    @Nested
    @SpringBootTest
    @AutoConfigureMockMvc
    class WithOidc {

        @DynamicPropertySource
        static void configuration(DynamicPropertyRegistry registry) {
            registry.add("leadgen.config-dir", OIDC_DIR::toString);
        }

        @MockitoBean
        private JwtDecoder decoder;

        @Autowired
        private MockMvcTester mvc;

        @Test
        void refusesARequestThatCarriesNoToken() {
            Assertions.assertThat(mvc.get().uri("/api/v1/status")).hasStatus(401);
        }

        @Test
        void refusesATokenTheIssuerDidNotSign() {
            // `BadJwtException` and not a plain `JwtException`: the provider reads the
            // first as a bad credential and answers 401, and the second as its own
            // infrastructure failing, which is a 500. An unreachable issuer is the second
            // kind, and it should not look to a caller like a rejected token.
            when(decoder.decode(anyString())).thenThrow(new BadJwtException("signature"));

            Assertions.assertThat(mvc.get()
                            .uri("/api/v1/status")
                            .header("Authorization", "Bearer forged"))
                    .hasStatus(401);
        }

        @Test
        void letsAVerifiedTokenThrough() {
            when(decoder.decode(anyString())).thenReturn(verified());

            Assertions.assertThat(mvc.get()
                            .uri("/api/v1/status")
                            .header("Authorization", "Bearer good"))
                    .hasStatusOk();
        }

        @Test
        void leavesTheContainersOwnHealthQuestionOpen() {
            // Asked before anything is up, and by nobody who could hold a token.
            Assertions.assertThat(mvc.get().uri("/actuator/health")).hasStatusOk();
        }

        @Test
        void saysHowToLogInWithoutRequiringSomebodyToHave() {
            // The bootstrap hole, and it is deliberate: a browser that cannot read this
            // cannot start the flow that would get it a token.
            Assertions.assertThat(mvc.get().uri("/api/v1/auth-config"))
                    .hasStatusOk()
                    .bodyJson()
                    .extractingPath("$.mode")
                    .isEqualTo("oidc");
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
    }
}
