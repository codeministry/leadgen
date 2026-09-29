/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.security;

import static org.assertj.core.api.Assertions.assertThat;

import de.codeministry.leadgen.Databases;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * {@link RebindingGuard} on a real port, behind Tomcat's RemoteIpValve, which MockMvc never runs.
 *
 * <p>The valve believes X-Forwarded-Host from any private or loopback peer, and a page on a rebound
 * name is same-origin, so it can set that header on a plain GET without a preflight. The guard reads
 * the Host the browser sent as well as the forwarded one, and refuses when either is a foreign name.
 * The requests are written to the socket by hand: the JDK's HTTP client does not let a caller set Host.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class RebindingGuardOverTheWireTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = Databases.postgres();

    @LocalServerPort
    private int port;

    @Test
    void refusesARebindingPageThatForwardsALocalHost() {
        assertThat(status("rebind.example.invalid:" + port, "X-Forwarded-Host: localhost\r\n"))
                .isEqualTo(403);
    }

    @Test
    void refusesALocalHostThatForwardsAForeignOne() {
        assertThat(status("localhost:" + port, "X-Forwarded-Host: rebind.example.invalid\r\n"))
                .isEqualTo(403);
        assertThat(status("localhost:" + port, "X-Forwarded-Host: localhost, rebind.example.invalid\r\n"))
                .isEqualTo(403);
    }

    @Test
    void servesALocalHostAndALocalForwardedOne() {
        assertThat(status("localhost:" + port, "")).isEqualTo(200);
        assertThat(status("localhost:" + port, "X-Forwarded-Host: localhost:4200\r\n"))
                .isEqualTo(200);
        assertThat(status("127.0.0.1:" + port, "")).isEqualTo(200);
    }

    /**
     * A page on a LAN address passes on its own origin only, and the port it is compared with is the
     * one the browser used: from the Host directly, from X-Forwarded-Port behind a proxy.
     */
    @Test
    void servesAPageOnItsOwnOriginByThePortTheBrowserUsed() {
        String lan = "192.168.0.10:" + port;
        assertThat(status(lan, "Origin: http://" + lan + "\r\n")).isEqualTo(200);
        assertThat(status(lan, "Origin: http://192.168.0.10:3000\r\n")).isEqualTo(403);

        String proxied = "X-Forwarded-Proto: http\r\nX-Forwarded-Host: 192.168.0.10:4200\r\nX-Forwarded-Port: 4200\r\n";
        assertThat(status("192.168.0.10", proxied + "Origin: http://192.168.0.10:4200\r\n"))
                .isEqualTo(200);
        assertThat(status("192.168.0.10", proxied + "Origin: http://192.168.0.10:" + port + "\r\n"))
                .isEqualTo(403);
    }

    private int status(String host, String extraHeaders) {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            OutputStream out = socket.getOutputStream();
            out.write(("GET /api/v1/status HTTP/1.1\r\nHost: " + host + "\r\n" + extraHeaders
                            + "Connection: close\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();
            InputStream in = socket.getInputStream();
            String statusLine = new String(in.readNBytes(12), StandardCharsets.US_ASCII);
            return Integer.parseInt(statusLine.substring(9, 12));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
