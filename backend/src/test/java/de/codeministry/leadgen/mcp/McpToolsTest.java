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

import de.codeministry.leadgen.Databases;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * leadgen serves its read tools over MCP itself (spec 023, ISC-481): a client on the wire lists
 * exactly the ten tools, the six codeministry-mcp served and four of the chat's. They read
 * leadgen's services in-process and open no connection of their own (ISC-483).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class McpToolsTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = Databases.postgres();

    /**
     * Every destination the JVM resolves a proxy for while it is installed. The JDK asks the default
     * {@link ProxySelector} before each HTTP request, whatever client sends it, and before each
     * plain socket connect. It goes in before the Spring context starts, because a client built at
     * startup keeps the selector it found then.
     *
     * <p>The selector is the whole JVM's, so in the full suite it also hears the connection pools of
     * other test classes' cached contexts topping up against their own databases. Each destination is
     * recorded with the thread that asked, and a socket a pool thread opens is a database connection,
     * whichever database; a tool's own connection would come from the thread running the tool.
     */
    private static final List<Outgoing> OUTGOING = new CopyOnWriteArrayList<>();

    private record Outgoing(URI uri, String thread) {}

    private static final ProxySelector PREVIOUS = ProxySelector.getDefault();

    private static final Pattern HTTP_CLIENT =
            Pattern.compile("RestClient|WebClient|HttpClient|RestTemplate|HttpURLConnection");

    static {
        ProxySelector.setDefault(new ProxySelector() {
            @Override
            public List<Proxy> select(URI uri) {
                OUTGOING.add(new Outgoing(uri, Thread.currentThread().getName()));
                return PREVIOUS == null ? List.of(Proxy.NO_PROXY) : PREVIOUS.select(uri);
            }

            @Override
            public void connectFailed(URI uri, SocketAddress address, IOException e) {
                if (PREVIOUS != null) {
                    PREVIOUS.connectFailed(uri, address, e);
                }
            }
        });
    }

    @AfterAll
    static void restoreProxySelector() {
        ProxySelector.setDefault(PREVIOUS);
    }

    @Autowired
    private JdbcTemplate jdbc;

    @LocalServerPort
    private int port;

    @Test
    void listsExactlyTheTenTools() {
        var client = McpTestClient.connect("http://localhost:" + port);

        assertThat(client.toolNames())
                .containsExactlyInAnyOrder(
                        "leadgen_search_offers",
                        "leadgen_get_offer",
                        "leadgen_funnel_stats",
                        "leadgen_ingest_status",
                        "leadgen_list_applications",
                        "leadgen_get_pipeline_config",
                        "leadgen_semantic_search",
                        "leadgen_statistics",
                        "leadgen_application",
                        "leadgen_profile");
    }

    /**
     * ISC-483: all ten are called, and the only destinations resolved meanwhile are the test's own
     * requests to {@code /mcp} and the database. A tool that asked leadgen's API over HTTP would show
     * as a request to another path of this server, one that asked anything else as another host.
     */
    @Test
    void theToolsOpenNoConnectionOfTheirOwn() {
        long offer = seedOfferWithApplication();
        var client = McpTestClient.connect("http://localhost:" + port);
        var calls = new LinkedHashMap<String, String>();
        calls.put("leadgen_search_offers", "{}");
        calls.put("leadgen_get_offer", "{\"id\":%d}".formatted(offer));
        calls.put("leadgen_funnel_stats", "{}");
        calls.put("leadgen_ingest_status", "{}");
        calls.put("leadgen_list_applications", "{}");
        calls.put("leadgen_get_pipeline_config", "{\"section\":\"rules\"}");
        calls.put("leadgen_semantic_search", "{\"query\":\"event streaming\"}");
        calls.put("leadgen_statistics", "{}");
        calls.put("leadgen_application", "{\"offerId\":%d}".formatted(offer));
        calls.put("leadgen_profile", "{}");
        assertThat(calls.keySet()).as("every tool is called").containsExactlyInAnyOrderElementsOf(client.toolNames());

        OUTGOING.clear();
        calls.forEach((tool, arguments) -> assertThat(
                        client.callTool(tool, arguments).path("isError").asBoolean(false))
                .as("%s answers", tool)
                .isFalse());
        var seen = List.copyOf(OUTGOING);

        assertThat(seen).as("the recorder saw the test's own requests").isNotEmpty();
        assertThat(seen).as("nothing but /mcp on this server and the database").allSatisfy(this::isAllowed);
    }

    /** No HTTP client is built or injected where the tools live. */
    @Test
    void theMcpPackageHoldsNoHttpClient() throws IOException {
        Path sources = Path.of("src/main/java/de/codeministry/leadgen/mcp");
        try (Stream<Path> files = Files.list(sources)) {
            var all = files.toList();
            assertThat(all).isNotEmpty();
            assertThat(all.stream()
                            .filter(file -> HTTP_CLIENT.matcher(read(file)).find())
                            .map(Path::getFileName))
                    .as("files in the mcp package that name an HTTP client")
                    .isEmpty();
        }
    }

    private void isAllowed(Outgoing outgoing) {
        URI uri = outgoing.uri();
        boolean ownMcp = ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                && uri.getPort() == port
                && "/mcp".equals(uri.getPath());
        boolean database = "socket".equals(uri.getScheme())
                && (uri.getPort() == POSTGRES.getFirstMappedPort()
                        || outgoing.thread().startsWith("HikariPool"));
        assertThat(ownMcp || database).as(outgoing.toString()).isTrue();
    }

    private long seedOfferWithApplication() {
        long source = jdbc.queryForObject(
                "INSERT INTO source (name, kind) VALUES ('probe', 'file') RETURNING id", Long.class);
        long offer = jdbc.queryForObject("""
                INSERT INTO offer (source_id, external_id, title, url, fingerprint, status, score_value, portal)
                VALUES (?, 'probe-1', 'Java Developer', 'https://example.invalid/probe', 'probe', 'PASSED', 80, 'portal-a')
                RETURNING id
                """, Long.class, source);
        jdbc.update("INSERT INTO application (offer_id, status) VALUES (?, 'SENT')", offer);
        return offer;
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
