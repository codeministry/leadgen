/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.concurrent;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.extension.ResponseTransformerV2;
import com.github.tomakehurst.wiremock.http.HttpHeader;
import com.github.tomakehurst.wiremock.http.HttpHeaders;
import com.github.tomakehurst.wiremock.http.Response;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import de.codeministry.leadgen.Databases;
import de.codeministry.leadgen.config.ConfigFixtures;
import de.codeministry.leadgen.config.ConfigRegistry;
import de.codeministry.leadgen.ingest.IngestReport;
import de.codeministry.leadgen.ingest.IngestService;
import de.codeministry.leadgen.llm.Vectors;
import de.codeministry.leadgen.retrieval.RetrievalReport;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
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
 * ISC-367: a whole run at width 4 does what a whole run at width 1 does — the same counts in
 * every stage's report, the same {@code pipeline_stage} rows in the same order — and the rows
 * of the model-bound stages say which width they ran at.
 *
 * <p>A sibling of {@link ConcurrentStagesTest} rather than a case in it: that class drives one
 * stage at a time over adverts it writes itself, while this one needs the run's own ingest, a
 * portal to fetch from and an embedding model, and every table empty before each pass so the
 * second run is not reading the first one's cache.
 *
 * <p>The adverts come in through the shipped {@code manual-inbox} source, which is
 * deterministic, and point at the stub for their pages. The chat stub answers every stage with
 * one object carrying every stage's key, since each reader takes only its own path out of the
 * answer. The embedding stub answers a vector of the column's width that depends on the text
 * alone, one hot component per advert, so no two adverts are near each other and the dedupe
 * stage has nothing to merge under either width.
 *
 * <p>Each run is also counted at the stub: every advert is asked about once by CONTENT, once by
 * FIELDS and once by SCORE, and nothing else in the run asks the chat endpoint, so equal reports
 * cannot come from a run that asked no model at all. With eight adverts and batches of thirty-two,
 * DEDUPE and RETRIEVAL send one embedding request each under either width — their bounded loop
 * runs at width 4 over a single batch, so what this class proves for them is the note and the
 * count, not overlap; overlap is {@code ConcurrentEmbeddingTest}'s. A stage that is switched off
 * at width 4 reports width 1, and its row carries no note.
 */
@SpringBootTest
@Testcontainers
class ConcurrentRunTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = Databases.postgres();

    private static final int ADVERTS = 8;
    private static final String LLM_WIDTH = "__TEST_LLM_WIDTH__";
    private static final String FETCH_WIDTH = "__TEST_FETCH_WIDTH__";
    private static final String RETRIEVAL = "__TEST_RETRIEVAL_ENABLED__";

    /** One chat request per advert from each of CONTENT, FIELDS and SCORE. */
    private static final int CHAT_REQUESTS = 3 * ADVERTS;

    /** One embedding request per advert batch of 32 from each of DEDUPE and RETRIEVAL. */
    private static final int EMBEDDING_REQUESTS = 2;

    private static final Set<String> MODEL_BOUND =
            Set.of("DEDUPE", "ENRICH", "CONTENT", "FIELDS", "SCORE", "RETRIEVAL");

    private static final String EVERY_STAGE_ANSWERED = """
            {"blocks":[],
             "start":{"text":"ab sofort","date":null},
             "duration":{"text":"6 Monate","months":6},
             "deadline":{"text":null,"date":null},
             "reasons":[{"factor":"role_fit","label":"backend engagement","points":15}]}
            """;

    private static final Embeddings EMBEDDINGS = new Embeddings();
    private static final WireMockServer MODEL;

    static {
        MODEL = new WireMockServer(WireMockConfiguration.options()
                .dynamicPort()
                .containerThreads(32)
                .extensions(EMBEDDINGS));
        MODEL.start();
    }

    private static String pipelineWithOpenWidths;

    private static final Path CONFIG = configPointingAtTheStub();

    @Autowired
    private IngestService ingest;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ConfigRegistry config;

    @DynamicPropertySource
    static void configuration(DynamicPropertyRegistry registry) {
        registry.add("leadgen.config-dir", () -> CONFIG.toString());
    }

    @AfterAll
    static void stop() {
        MODEL.stop();
    }

    @AfterEach
    void sequentialAgain() {
        widths(1);
    }

    @Test
    void aRunAtWidthFourCountsAndRecordsWhatARunAtWidthOneDoes() {
        IngestReport sequential = run(1);
        List<Map<String, Object>> sequentialStages = stageRows();
        assertEveryAdvertAskedOnce(sequential);

        IngestReport parallel = run(4);
        List<Map<String, Object>> parallelStages = stageRows();
        assertEveryAdvertAskedOnce(parallel);

        // The fixture has to reach every model-bound stage, or equal reports prove nothing.
        assertThat(sequential.written()).isEqualTo(ADVERTS);
        assertThat(sequential.enriched().considered()).isEqualTo(ADVERTS);
        assertThat(sequential.segmented().considered()).isEqualTo(ADVERTS);
        assertThat(sequential.fields().considered()).isEqualTo(ADVERTS);
        assertThat(sequential.indexed().embedded()).isEqualTo(ADVERTS);

        assertThat(parallel)
                .usingRecursiveComparison()
                .ignoringFields(
                        "finishedAt",
                        "enriched.width",
                        "segmented.width",
                        "fields.width",
                        "scored.width",
                        "indexed.width")
                .isEqualTo(sequential);
        assertThat(parallelStages)
                .extracting(row -> List.of(row.get("position"), row.get("stage"), row.get("status")))
                .containsExactlyElementsOf(sequentialStages.stream()
                        .map(row -> List.of(row.get("position"), row.get("stage"), row.get("status")))
                        .toList());

        assertThat(sequentialStages)
                .allSatisfy(row -> assertThat(row.get("note")).isNull());
        assertThat(parallelStages)
                .allSatisfy(row -> assertThat(row.get("note"))
                        .isEqualTo(MODEL_BOUND.contains((String) row.get("stage")) ? "width=4" : null));
        // The note and the report's width are the same number, the one each log line named.
        assertThat(List.of(
                        sequential.enriched().width(),
                        sequential.segmented().width(),
                        sequential.fields().width(),
                        sequential.scored().width(),
                        sequential.indexed().width()))
                .containsOnly(1);
        assertThat(List.of(
                        parallel.enriched().width(),
                        parallel.segmented().width(),
                        parallel.fields().width(),
                        parallel.scored().width(),
                        parallel.indexed().width()))
                .containsOnly(4);
        assertThat(parallelStages)
                .extracting(row -> row.get("stage"))
                .containsAll(MODEL_BOUND.stream().map(Object.class::cast).toList());
    }

    @Test
    void aStageSwitchedOffAtWidthFourNamesNoWidth() {
        IngestReport report = run(4, false);
        List<Map<String, Object>> stages = stageRows();

        assertThat(report.indexed()).isEqualTo(RetrievalReport.skipped());
        assertThat(report.indexed().width()).isEqualTo(1);
        assertThat(stages)
                .filteredOn(row -> "RETRIEVAL".equals(row.get("stage")))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.get("status")).isEqualTo("OK");
                    assertThat(row.get("note")).isNull();
                });
        // The rest of the run still ran wide, so the null above is the skip and not the width.
        assertThat(stages)
                .filteredOn(row -> "SCORE".equals(row.get("stage")))
                .singleElement()
                .satisfies(row -> assertThat(row.get("note")).isEqualTo("width=4"));
        // Only DEDUPE embedded; RETRIEVAL asked nothing.
        MODEL.verify(1, postRequestedFor(urlPathEqualTo("/embeddings")));
    }

    /** Every advert went to each model-bound chat stage once, and the stub saw exactly that. */
    private static void assertEveryAdvertAskedOnce(IngestReport report) {
        assertThat(report.segmented().requests()).isEqualTo(ADVERTS);
        assertThat(report.fields().requests()).isEqualTo(ADVERTS);
        assertThat(report.scored().scored()).isEqualTo(ADVERTS);
        assertThat(report.indexed().requests()).isEqualTo(1);
        MODEL.verify(CHAT_REQUESTS, postRequestedFor(urlPathEqualTo("/chat/completions")));
        MODEL.verify(EMBEDDING_REQUESTS, postRequestedFor(urlPathEqualTo("/embeddings")));
    }

    // ---- fixture --------------------------------------------------------------------------

    private IngestReport run(int width) {
        return run(width, true);
    }

    private IngestReport run(int width, boolean retrieval) {
        empty();
        widths(width, retrieval);
        MODEL.resetRequests();
        return ingest.run();
    }

    private List<Map<String, Object>> stageRows() {
        return jdbc.queryForList("SELECT position, stage, status, note FROM pipeline_stage ORDER BY run_id, position");
    }

    /** Every table but Flyway's, so the second run fetches, labels and embeds from nothing. */
    private void empty() {
        List<String> tables = jdbc.queryForList(
                "SELECT quote_ident(tablename) FROM pg_tables WHERE schemaname = 'public'"
                        + " AND tablename <> 'flyway_schema_history'",
                String.class);
        jdbc.execute("TRUNCATE " + String.join(", ", tables) + " RESTART IDENTITY CASCADE");
    }

    /** Both widths at once: the model's and the fetch's. */
    private void widths(int width) {
        widths(width, true);
    }

    private void widths(int width, boolean retrieval) {
        try {
            Files.writeString(
                    CONFIG.resolve("pipeline.yaml"),
                    pipelineWithOpenWidths
                            .replace(LLM_WIDTH, String.valueOf(width))
                            .replace(FETCH_WIDTH, String.valueOf(width))
                            .replace(RETRIEVAL, String.valueOf(retrieval)),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        config.reload();
        assertThat(config.snapshot().application().llm().concurrency()).isEqualTo(width);
        assertThat(config.snapshot().application().enrichment().fetch().concurrency())
                .isEqualTo(width);
    }

    private static Path configPointingAtTheStub() {
        try {
            Path dir = Files.createTempDirectory("leadgen-concurrent-run");
            dir.toFile().deleteOnExit();
            ConfigFixtures.materialize(dir);

            Path inbox = Files.createDirectories(dir.resolve("inbox"));
            for (int index = 0; index < ADVERTS; index++) {
                Files.writeString(
                        inbox.resolve("offer-" + index + ".md"),
                        """
                        ---
                        title: Java Entwickler %1$d (Remote)
                        url: %2$s/ad/%1$d
                        location: Remote
                        published: %3$s
                        tags: [Java, Spring Boot]
                        ---
                        Projekt Nummer %1$d, freiberuflich.
                        """.formatted(index, MODEL.baseUrl(), java.time.LocalDate.now()),
                        StandardCharsets.UTF_8);
                MODEL.stubFor(get(urlPathEqualTo("/ad/" + index))
                        .willReturn(aResponse()
                                .withHeader("Content-Type", "text/html; charset=utf-8")
                                .withFixedDelay(50)
                                .withBody("<html><body><article><p>Wir suchen für Projekt Nummer %1$d einen"
                                                .formatted(index)
                                        + " erfahrenen Java-Entwickler, remote, freiberuflich.</p>"
                                        + "<p>Das Team Nummer %1$d arbeitet mit Spring Boot und Kafka.</p>"
                                                .formatted(index)
                                        + "</article></body></html>")));
            }
            MODEL.stubFor(post(urlPathEqualTo("/chat/completions"))
                    .willReturn(aResponse()
                            .withHeader("Content-Type", "application/json")
                            .withFixedDelay(20)
                            .withBody(completion(EVERY_STAGE_ANSWERED))));
            MODEL.stubFor(post(urlPathEqualTo("/embeddings")).willReturn(ok().withTransformers(Embeddings.NAME)));

            Path pipeline = dir.resolve("pipeline.yaml");
            String text = Files.readString(pipeline, StandardCharsets.UTF_8);
            text = set(text, "provider", "openai-compatible");
            text = set(text, "base_url", MODEL.baseUrl());
            text = set(text, "api_key", "test-key");
            text = set(text, "scoring", "test-model");
            text = set(text, "embedding", "test-embed");
            text = set(text, "max_calls_per_day", "1000000");
            text = set(text, "rate_limit_per_minute", "6000");
            text = set(text, "respect_robots_txt", "false");
            text = replace(text, "${RETRIEVAL_ENABLED:false}", RETRIEVAL);
            text = replace(
                    text, "${PACKAGES_DIR:./packages}", dir.resolve("packages").toString());
            text = replace(
                    text,
                    "${DIGEST_DIR:./packages/digest}",
                    dir.resolve("packages/digest").toString());
            text = replace(text, "${LLM_CONCURRENCY:1}", LLM_WIDTH);
            text = replace(text, "${FETCH_CONCURRENCY:1}", FETCH_WIDTH);
            text = ConfigFixtures.closePlaceholders(text);
            pipelineWithOpenWidths = text;
            Files.writeString(
                    pipeline,
                    text.replace(LLM_WIDTH, "1").replace(FETCH_WIDTH, "1").replace(RETRIEVAL, "true"),
                    StandardCharsets.UTF_8);
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String completion(String answer) {
        try {
            return new ObjectMapper()
                    .writeValueAsString(Map.of(
                            "id",
                            "chatcmpl-1",
                            "object",
                            "chat.completion",
                            "created",
                            1,
                            "model",
                            "test-model",
                            "choices",
                            List.of(Map.of(
                                    "index",
                                    0,
                                    "message",
                                    Map.of("role", "assistant", "content", answer),
                                    "finish_reason",
                                    "stop"))));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String replace(String yaml, String placeholder, String value) {
        if (!yaml.contains(placeholder)) {
            throw new IllegalStateException("no `" + placeholder + "` in the shipped pipeline.yaml");
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

    /**
     * One vector per input, of the column's width, hot in the one component the text hashes to:
     * the same text always gets the same vector, and two adverts are orthogonal.
     */
    static final class Embeddings implements ResponseTransformerV2 {

        static final String NAME = "concurrent-run-embeddings";
        private static final ObjectMapper JSON = new ObjectMapper();

        @Override
        public String getName() {
            return NAME;
        }

        @Override
        public boolean applyGlobally() {
            return false;
        }

        @Override
        public Response transform(Response response, ServeEvent serveEvent) {
            try {
                JsonNode input =
                        JSON.readTree(serveEvent.getRequest().getBodyAsString()).get("input");
                List<String> texts = input.isArray()
                        ? IntStream.range(0, input.size())
                                .mapToObj(index -> input.get(index).asText())
                                .toList()
                        : List.of(input.asText());
                String data = IntStream.range(0, texts.size())
                        .mapToObj(index -> "{\"object\":\"embedding\",\"index\":%d,\"embedding\":[%s]}"
                                .formatted(index, vector(texts.get(index))))
                        .collect(Collectors.joining(","));
                return Response.Builder.like(response)
                        .but()
                        .status(200)
                        .headers(new HttpHeaders(HttpHeader.httpHeader("Content-Type", "application/json")))
                        .body("""
                                {"object":"list","model":"test-embed",
                                 "usage":{"prompt_tokens":1,"total_tokens":1},
                                 "data":[%s]}
                                """.formatted(data))
                        .build();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        private static String vector(String text) {
            int hot = Math.floorMod(text.hashCode(), Vectors.DIMENSIONS);
            return IntStream.range(0, Vectors.DIMENSIONS)
                    .mapToObj(index -> index == hot ? "1.0" : "0.0")
                    .collect(Collectors.joining(","));
        }
    }
}
