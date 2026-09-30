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
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Under {@code security.auth: none} a page on a name its author points at this machine (DNS
 * rebinding) talks to the API as if it were its own: a same-origin request, no preflight, reads
 * included. {@link RebindingGuard} refuses a request whose Host, or whose Origin, is a dotted name
 * nobody configured; loopback, IP literals and single-label service names pass, and so does the
 * container's health question.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(
        properties =
                "leadgen.security.allowed-hosts=leadgen.example.invalid, lan.example.invalid:4200, dot.example.invalid., my_box.example.invalid, http://scheme.example.invalid:4200/, bücher.example.invalid")
class RebindingGuardTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = Databases.postgres();

    @Autowired
    private MockMvcTester mvc;

    @Test
    void refusesARequestForAForeignName() {
        Assertions.assertThat(mvc.get().uri("/api/v1/status").with(host("attacker.example")))
                .hasStatus(403);
    }

    @Test
    void servesLoopbackIpLiteralsAndSingleLabelNames() {
        for (String name : new String[] {"localhost", "127.0.0.1", "[::1]", "192.168.0.10", "leadgen-api"}) {
            Assertions.assertThat(mvc.get().uri("/api/v1/status").with(host(name)))
                    .as(name)
                    .hasStatusOk();
        }
    }

    @Test
    void servesAConfiguredName() {
        Assertions.assertThat(mvc.get().uri("/api/v1/status").with(host("leadgen.example.invalid")))
                .hasStatusOk();
    }

    /** An entry with a port or a trailing dot names the host, and so does a request with a trailing dot. */
    @Test
    void readsAConfiguredNameTheWayAnOperatorWritesIt() {
        for (String name : new String[] {"lan.example.invalid", "dot.example.invalid", "leadgen.example.invalid."}) {
            Assertions.assertThat(mvc.get().uri("/api/v1/status").with(host(name)))
                    .as(name)
                    .hasStatusOk();
        }
    }

    /**
     * An Origin is a page, and the pages that may talk to the API unauthenticated are local ones, the
     * configured hosts and the host the request was sent to: an IP literal or a single-label name
     * passes as a Host (a probe, a service) but not as some other page sending the request.
     */
    @Test
    void acceptsLocalAndConfiguredOriginsOnly() {
        for (String origin : new String[] {"http://203.0.113.9", "http://intranet", "http://192.168.0.10:4200"}) {
            Assertions.assertThat(mvc.get()
                            .uri("/api/v1/status")
                            .with(host("localhost"))
                            .header("Origin", origin))
                    .as(origin)
                    .hasStatus(403);
        }
        for (String origin : new String[] {
            "http://localhost:4200", "http://127.0.0.1:4200", "http://[::1]:4200", "https://leadgen.example.invalid"
        }) {
            Assertions.assertThat(mvc.get()
                            .uri("/api/v1/status")
                            .with(host("localhost"))
                            .header("Origin", origin))
                    .as(origin)
                    .hasStatusOk();
        }
    }

    /**
     * The UI opened by a LAN address or a bare machine name is a page on the origin the request goes
     * to, host and port, so it writes as well as reads; a page on another host, or on another port of
     * the same one, is cross-origin.
     */
    @Test
    void servesAPageOnTheOriginTheRequestWasSentTo() {
        for (Object[] row : new Object[][] {
            {"192.168.0.10", 4200, "http://192.168.0.10:4200"},
            {"intranet", 80, "http://intranet"},
            {"[::1]", 80, "http://[::1]"}
        }) {
            Assertions.assertThat(write((String) row[0], (Integer) row[1], (String) row[2]))
                    .as((String) row[2])
                    .isNotEqualTo(403);
        }
        Assertions.assertThat(write("192.168.0.10", 4200, "http://203.0.113.9")).isEqualTo(403);
        Assertions.assertThat(write("192.168.0.10", 4200, "http://192.168.0.10:3000"))
                .isEqualTo(403);
        Assertions.assertThat(write("intranet", 80, "https://intranet")).isEqualTo(403);
    }

    /** An IP literal is what {@code InetAddress} parses as one, not whatever has digits or a colon. */
    @Test
    void refusesANameThatOnlyLooksLikeAnAddress() {
        Assertions.assertThat(mvc.get().uri("/api/v1/status").with(host("999.999.999.999")))
                .hasStatus(403);
        Assertions.assertThat(mvc.get()
                        .uri("/api/v1/status")
                        .with(host("localhost"))
                        .header("X-Forwarded-Host", "evil.example:1:2"))
                .hasStatus(403);
    }

    /** An entry copied from the address bar, scheme and path included, names its host. */
    @Test
    void readsAConfiguredEntryWithItsScheme() {
        Assertions.assertThat(mvc.get().uri("/api/v1/status").with(host("scheme.example.invalid")))
                .hasStatusOk();
    }

    /** A Unicode entry names the punycode host a browser sends for it. */
    @Test
    void readsAConfiguredEntryInUnicode() {
        Assertions.assertThat(mvc.get().uri("/api/v1/status").with(host("xn--bcher-kva.example.invalid")))
                .hasStatusOk();
    }

    /** A configured name a browser serves but {@code java.net.URI} cannot parse is a page all the same. */
    @Test
    void readsAnOriginWithAnUnderscoreInItsName() {
        Assertions.assertThat(mvc.get()
                        .uri("/api/v1/status")
                        .with(host("localhost"))
                        .header("Origin", "http://my_box.example.invalid:4200"))
                .hasStatusOk();
    }

    @Test
    void refusesAPageFromAForeignOriginAndServesALocalOne() {
        Assertions.assertThat(mvc.get()
                        .uri("/api/v1/status")
                        .with(host("localhost"))
                        .header("Origin", "http://attacker.example"))
                .hasStatus(403);
        Assertions.assertThat(
                        mvc.get().uri("/api/v1/status").with(host("localhost")).header("Origin", "null"))
                .hasStatus(403);
        Assertions.assertThat(mvc.get()
                        .uri("/api/v1/status")
                        .with(host("localhost"))
                        .header("Origin", "http://localhost:4200"))
                .hasStatusOk();
    }

    @Test
    void leavesTheContainersHealthQuestionOpen() {
        Assertions.assertThat(mvc.get().uri("/actuator/health").with(host("attacker.example")))
                .hasStatusOk();
    }

    /** A body-carrying write from a page, to a host and port; the status the guard leaves it with. */
    private int write(String name, int port, String origin) {
        return mvc.post()
                .uri("/api/v1/chat/conversations/bulk-delete")
                .with(request -> {
                    request.setServerName(name);
                    request.setServerPort(port);
                    request.addHeader("Host", port == 80 ? name : name + ":" + port);
                    return request;
                })
                .header("Origin", origin)
                .contentType("application/json")
                .content("{\"ids\":[]}")
                .exchange()
                .getResponse()
                .getStatus();
    }

    private static RequestPostProcessor host(String name) {
        return request -> {
            request.setServerName(name);
            request.addHeader("Host", name);
            return request;
        };
    }
}
