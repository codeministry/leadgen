/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.dedupe;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.extension.ResponseTransformerV2;
import com.github.tomakehurst.wiremock.http.Response;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import de.codeministry.leadgen.Databases;
import de.codeministry.leadgen.config.ConfigFixtures;
import de.codeministry.leadgen.config.ConfigRegistry;
import de.codeministry.leadgen.ingest.extract.TitleNormalizer;
import de.codeministry.leadgen.llm.Vectors;
import de.codeministry.leadgen.retrieval.RetrievalIndexService;
import de.codeministry.leadgen.retrieval.RetrievalReport;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * DEDUPE's embedding pass and RETRIEVAL submit their 32-advert batches under
 * {@code llm.concurrency}, and a refused budget or a failed batch leaves exactly those adverts
 * without a vector and due again.
 *
 * <p>Seventy adverts are three batches — 32, 32 and 6 — and the configuration here says width 4,
 * so all three fit at once. The stub answers each request after a pause and counts how many it
 * is answering at the same moment; a sequential loop never gets that count above one. The
 * answer is built from the request, one vector per input, because a fixed body of 32 would make
 * the batch of 6 a size mismatch and prove nothing about it.
 *
 * <p>The overlap cases are the probe proper: they were red while both services still walked
 * their batches one after another, and turned green only once the batches went out under the
 * bound. A second width, 2, pins the bound itself — at width 4 three batches never reach it, so
 * that case alone could not tell a bound from none. The budget and failed-batch cases are a
 * regression guard: that half already held when the batches ran in sequence, and it has to keep
 * holding now that a refusal can arrive while other batches are in flight. They check the
 * adverts left due by identity, not only by count.
 *
 * <p>Both services sit in one class because they share the context, the stub and the shape of the
 * proof; only the column and the due predicate differ.
 */
@SpringBootTest
@Testcontainers
class ConcurrentEmbeddingTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = Databases.postgres();

    private static final int WIDTH = 4;
    private static final int ADVERTS = 70;
    private static final String WIDTH_PLACEHOLDER = "${LLM_CONCURRENCY:1}";

    /** In a title, it makes the stub refuse the request that carries it. */
    private static final String FAILS = "SCHEITERT";

    private static final InFlight IN_FLIGHT = new InFlight();
    private static final WireMockServer MODEL;

    static {
        MODEL = new WireMockServer(WireMockConfiguration.options().dynamicPort().extensions(IN_FLIGHT));
        MODEL.start();
    }

    /** The fixture's pipeline.yaml with the width placeholder still open, so a case can set another. */
    private static String pipelineWithOpenWidth;

    private static final Path CONFIG = configPointingAtTheStub();

    @Autowired
    private OfferEmbedder embedder;

    @Autowired
    private RetrievalIndexService retrieval;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ConfigRegistry config;

    private long sourceId;

    @DynamicPropertySource
    static void configuration(DynamicPropertyRegistry registry) {
        registry.add("leadgen.config-dir", () -> CONFIG.toString());
    }

    @AfterAll
    static void stop() {
        MODEL.stop();
    }

    @BeforeEach
    void reset() {
        MODEL.resetAll();
        MODEL.stubFor(post(urlPathEqualTo("/embeddings")).willReturn(ok()));
        IN_FLIGHT.reset();
        jdbc.update("DELETE FROM offer");
        jdbc.update("DELETE FROM source");
        jdbc.update("DELETE FROM llm_call_budget");
        sourceId =
                jdbc.queryForObject("INSERT INTO source (name, kind) VALUES ('test', 'file') RETURNING id", Long.class);
    }

    @Test
    void dedupeSendsItsThreeBatchesAtOnce() {
        adverts(ADVERTS, -1);

        assertThat(embedder.embed(60)).isEqualTo(ADVERTS);

        assertThat(IN_FLIGHT.highWater()).isEqualTo(3);
        assertThat(dedupeDue()).isZero();
    }

    @Test
    void dedupeNeverHasMoreThanTheWidthInFlight() {
        // Three batches at width 2: an unbounded submit would reach 3, a sequential loop 1.
        adverts(ADVERTS, -1);
        width(2);
        try {
            assertThat(embedder.embed(60)).isEqualTo(ADVERTS);

            assertThat(IN_FLIGHT.highWater()).isEqualTo(2);
            assertThat(dedupeDue()).isZero();
        } finally {
            width(WIDTH);
        }
    }

    @Test
    void dedupeWithOneCallLeftWritesOneBatchAndLeavesTheRestDue() {
        // Width 4 hands out more than one batch before the refusal is known; the budget is one
        // atomic UPDATE, so only one of them is ever sent and the others write nothing. Which
        // batch asks first is a race above width 1, so the written rows are one whole batch —
        // 32 or the last 6 — and every other advert is still due.
        adverts(ADVERTS, -1);
        oneCallLeft();

        int written = embedder.embed(60);

        assertThat(written).isIn(32, 6);
        assertThat(dedupeDue()).isEqualTo(ADVERTS - written);
        assertThat(writtenIds("embedding")).isIn(batchesOfIds());
        MODEL.verify(1, postRequestedFor(urlPathEqualTo("/embeddings")));
    }

    @Test
    void dedupeLeavesExactlyAFailedBatchDue() {
        // The second batch is refused by the endpoint; the first and the third are written.
        adverts(ADVERTS, 40);

        assertThat(embedder.embed(60)).isEqualTo(38);

        assertThat(dedupeDue()).isEqualTo(32);
        assertThat(writtenIds("embedding")).isEqualTo(firstAndLastBatch());
    }

    @Test
    void retrievalSendsItsThreeBatchesAtOnce() {
        passed(adverts(ADVERTS, -1));

        RetrievalReport report = retrieval.run();

        assertThat(report).isEqualTo(new RetrievalReport(ADVERTS, ADVERTS, 3, "test-embed", configuredWidth()));
        assertThat(IN_FLIGHT.highWater()).isEqualTo(3);
        assertThat(retrievalDue()).isZero();
    }

    @Test
    void retrievalWithOneCallLeftWritesOneBatchAndLeavesTheRestDue() {
        passed(adverts(ADVERTS, -1));
        oneCallLeft();

        RetrievalReport report = retrieval.run();

        // One whole batch, whichever asked first; see the dedupe case.
        assertThat(report.embedded()).isIn(32, 6);
        assertThat(report)
                .isEqualTo(new RetrievalReport(ADVERTS, report.embedded(), 1, "test-embed", configuredWidth()));
        assertThat(retrievalDue()).isEqualTo(ADVERTS - report.embedded());
        assertThat(writtenIds("retrieval_embedding")).isIn(batchesOfIds());
        MODEL.verify(1, postRequestedFor(urlPathEqualTo("/embeddings")));
    }

    @Test
    void retrievalLeavesExactlyAFailedBatchDue() {
        passed(adverts(ADVERTS, 40));

        RetrievalReport report = retrieval.run();

        // A failed request still left for the model, so it counts as one.
        assertThat(report).isEqualTo(new RetrievalReport(ADVERTS, 38, 3, "test-embed", configuredWidth()));
        assertThat(retrievalDue()).isEqualTo(32);
        assertThat(writtenIds("retrieval_embedding")).isEqualTo(firstAndLastBatch());
    }

    /** The width the report names: the one the loop ran at, which is the configured one here. */
    private int configuredWidth() {
        return config.snapshot().application().llm().concurrency();
    }

    /** The ids in the three batches the services cut: the first 32, the next 32, the last 6. */
    private List<List<Long>> batchesOfIds() {
        List<Long> all = jdbc.queryForList("SELECT id FROM offer ORDER BY id", Long.class);
        return List.of(all.subList(0, 32), all.subList(32, 64), all.subList(64, ADVERTS));
    }

    /** Batch 1 and batch 3 in id order: what is written when the second batch is refused. */
    private List<Long> firstAndLastBatch() {
        List<List<Long>> batches = batchesOfIds();
        List<Long> ids = new ArrayList<>(batches.get(0));
        ids.addAll(batches.get(2));
        return ids;
    }

    /** Column names are this class's own constants, never input. */
    private List<Long> writtenIds(String column) {
        return jdbc.queryForList("SELECT id FROM offer WHERE " + column + " IS NOT NULL ORDER BY id", Long.class);
    }

    /** The loaded ceiling less one, so the fixture follows the shipped default rather than a copy of it. */
    private void oneCallLeft() {
        int limit = config.snapshot().application().llm().budget().maxCallsPerDay();
        jdbc.update("INSERT INTO llm_call_budget (day, calls) VALUES (current_date, ?)", limit - 1);
    }

    /** Rewrites the fixture's width and reloads it; the services read the snapshot per pass. */
    private void width(int width) {
        try {
            Files.writeString(
                    CONFIG.resolve("pipeline.yaml"),
                    replaceOnce(pipelineWithOpenWidth, WIDTH_PLACEHOLDER, String.valueOf(width)),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        assertThat(config.reload()).isTrue();
        assertThat(config.snapshot().application().llm().concurrency()).isEqualTo(width);
    }

    private int dedupeDue() {
        return jdbc.queryForObject("SELECT count(*) FROM offer WHERE embedding IS NULL", Integer.class);
    }

    private int retrievalDue() {
        return jdbc.queryForObject("SELECT count(*) FROM offer WHERE retrieval_embedded_at IS NULL", Integer.class);
    }

    private void passed(int count) {
        jdbc.update("""
                UPDATE offer
                   SET content_blocks = jsonb_build_array(jsonb_build_object(
                           'index', 0, 'kind', 'CONTENT', 'text', title, 'reason', 't', 'by', 'RULE')),
                       full_text = title,
                       content_at = now(),
                       status = 'PASSED'
                """);
    }

    /**
     * {@code count} adverts, inserted in id order so the batches are the first 32, the next 32
     * and the last 6. The advert at {@code failing} carries the marker that makes its whole
     * request fail; -1 means none does.
     */
    private int adverts(int count, int failing) {
        for (int index = 0; index < count; index++) {
            String title = "Anzeige " + index + (index == failing ? " " + FAILS : "");
            jdbc.update(
                    """
                    INSERT INTO offer (source_id, external_id, title, description, url, location, portal, fingerprint)
                    VALUES (?, ?, ?, 'Beschreibung.', ?, 'Köln', 'portal-a', ?)
                    """,
                    sourceId,
                    "ext-" + index,
                    title,
                    "https://example.invalid/" + index,
                    TitleNormalizer.normalize(title));
        }
        return count;
    }

    /**
     * Answers every embedding request itself, after a pause long enough that three requests sent
     * together overlap, and records the most it was answering at once.
     */
    static final class InFlight implements ResponseTransformerV2 {

        private static final ObjectMapper JSON = new ObjectMapper();
        private static final String VECTOR = IntStream.range(0, Vectors.DIMENSIONS)
                .mapToObj(index -> String.valueOf(index / (double) Vectors.DIMENSIONS))
                .collect(Collectors.joining(","));

        private final AtomicInteger now = new AtomicInteger();
        private final AtomicInteger most = new AtomicInteger();

        void reset() {
            now.set(0);
            most.set(0);
        }

        int highWater() {
            return most.get();
        }

        @Override
        public Response transform(Response response, ServeEvent serveEvent) {
            most.accumulateAndGet(now.incrementAndGet(), Math::max);
            try {
                Thread.sleep(600);
                String body = serveEvent.getRequest().getBodyAsString();
                if (body.contains(FAILS)) {
                    return Response.Builder.like(response)
                            .but()
                            .status(400)
                            .body("{\"error\":{\"message\":\"refused\"}}")
                            .build();
                }
                JsonNode input = JSON.readTree(body).get("input");
                int count = input.isArray() ? input.size() : 1;
                String data = IntStream.range(0, count)
                        .mapToObj(index ->
                                "{\"object\":\"embedding\",\"index\":%d,\"embedding\":[%s]}".formatted(index, VECTOR))
                        .collect(Collectors.joining(","));
                return Response.Builder.like(response)
                        .but()
                        .status(200)
                        .headers(new com.github.tomakehurst.wiremock.http.HttpHeaders(
                                com.github.tomakehurst.wiremock.http.HttpHeader.httpHeader(
                                        "Content-Type", "application/json")))
                        .body("""
                                {"object":"list","model":"test-embed",
                                 "usage":{"prompt_tokens":1,"total_tokens":1},
                                 "data":[%s]}
                                """.formatted(data))
                        .build();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } finally {
                now.decrementAndGet();
            }
        }

        @Override
        public String getName() {
            return "in-flight";
        }
    }

    /**
     * The shipped defaults with the llm block pointed at the stub, the width set to 4 and the
     * retrieval stage switched on. No vendor is named and no placeholder is left open.
     */
    private static Path configPointingAtTheStub() {
        try {
            Path dir = Files.createTempDirectory("leadgen-concurrent-embedding");
            dir.toFile().deleteOnExit();
            ConfigFixtures.materialize(dir);

            Path pipeline = dir.resolve("pipeline.yaml");
            String text = Files.readString(pipeline, StandardCharsets.UTF_8);
            text = set(text, "provider", "openai-compatible");
            text = set(text, "base_url", MODEL.baseUrl());
            text = set(text, "api_key", "test-key");
            text = set(text, "embedding", "test-embed");
            text = replaceOnce(text, "${RETRIEVAL_ENABLED:false}", "true");
            // By placeholder, not by key: `concurrency:` also names the fetch width.
            pipelineWithOpenWidth = text;
            text = replaceOnce(text, WIDTH_PLACEHOLDER, String.valueOf(WIDTH));
            Files.writeString(pipeline, text, StandardCharsets.UTF_8);
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String replaceOnce(String yaml, String placeholder, String value) {
        if (!yaml.contains(placeholder)) {
            throw new IllegalStateException(
                    "no `" + placeholder + "` in the shipped pipeline.yaml — the fixture and the file have drifted");
        }
        return yaml.replace(placeholder, value);
    }

    private static String set(String yaml, String key, String value) {
        Matcher matcher =
                Pattern.compile("(?m)^([ \\t]*)" + Pattern.quote(key) + ":.*$").matcher(yaml);
        if (!matcher.find()) {
            throw new IllegalStateException(
                    "no `" + key + ":` in the shipped pipeline.yaml — the fixture and the file have drifted");
        }
        return matcher.replaceFirst("$1" + key + ": " + Matcher.quoteReplacement(value));
    }
}
