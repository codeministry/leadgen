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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import de.codeministry.leadgen.Databases;
import de.codeministry.leadgen.config.ConfigFixtures;
import de.codeministry.leadgen.config.ConfigRegistry;
import de.codeministry.leadgen.content.ContentService;
import de.codeministry.leadgen.fields.FieldsService;
import de.codeministry.leadgen.score.ScoringService;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
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
 * ISC-363 and ISC-366: CONTENT, FIELDS and the synchronous SCORE work up to
 * {@code llm.concurrency} adverts at once, and the day's budget holds under any width.
 *
 * <p>The model is a stub that answers a whole chat completion after 200 ms — a body that is not a
 * whole completion is rejected by the provider SDK before the stage's reader sees it, and the
 * stage would then record "no answer" for every advert, which proves nothing about a width.
 * Eight due adverts at width 1 cannot finish in under 1.6 s; at width 4 they are two rounds of
 * four and finish well under 0.8 s. Each stage's rows are compared across the two widths with
 * the timestamps left out, so a faster run that wrote something else would fail here.
 *
 * <p>Writes are counted per advert by a trigger on the stage's own stamp column, installed by the
 * test on its own database: a body run twice for one advert, or two workers handed the same
 * advert, would show as a second row in {@code test_write_log} even when the end state looked
 * right.
 */
@SpringBootTest
@Testcontainers
class ConcurrentStagesTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = Databases.postgres();

    private static final int ADVERTS = 8;
    private static final int DELAY_MS = 200;
    private static final String WIDTH_MARKER = "__TEST_LLM_WIDTH__";
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String JUDGED = """
            {"reasons":[{"factor":"role_fit","label":"backend engagement","points":15}]}
            """;
    private static final String FIELDS_READ = """
            {"start":{"text":"ab sofort","date":null},
             "duration":{"text":"6 Monate","months":6},
             "deadline":{"text":null,"date":null}}
            """;
    private static final String NOTHING_IS_CHROME = "{\"blocks\":[]}";

    private static final WireMockServer MODEL;

    static {
        MODEL = new WireMockServer(WireMockConfiguration.options().dynamicPort().containerThreads(32));
        MODEL.start();
    }

    /** The fixture's pipeline.yaml with the llm width left as a marker, so a case can set it. */
    private static String pipelineWithOpenWidth;

    private static final Path CONFIG = configPointingAtTheStub();

    @Autowired
    private ScoringService scoring;

    @Autowired
    private FieldsService fields;

    @Autowired
    private ContentService content;

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
        jdbc.execute("CREATE TABLE IF NOT EXISTS test_write_log (stage text NOT NULL, offer_id bigint NOT NULL)");
        // The writing transaction as well, so a width-4 SCORE can be shown to commit each offer on
        // its own and its reasons with it, rather than inferred from the annotation.
        jdbc.execute("ALTER TABLE test_write_log ADD COLUMN IF NOT EXISTS txid bigint");
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION test_log_write() RETURNS trigger AS $$
                BEGIN
                    INSERT INTO test_write_log (stage, offer_id, txid) VALUES (TG_ARGV[0], NEW.id, txid_current());
                    RETURN NEW;
                END
                $$ LANGUAGE plpgsql
                """);
        jdbc.execute("""
                CREATE OR REPLACE TRIGGER test_log_score AFTER UPDATE ON offer FOR EACH ROW
                WHEN (NEW.scored_at IS DISTINCT FROM OLD.scored_at) EXECUTE FUNCTION test_log_write('SCORE')
                """);
        jdbc.execute("""
                CREATE OR REPLACE TRIGGER test_log_fields AFTER UPDATE ON offer FOR EACH ROW
                WHEN (NEW.fields_at IS DISTINCT FROM OLD.fields_at) EXECUTE FUNCTION test_log_write('FIELDS')
                """);
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION test_log_reason() RETURNS trigger AS $$
                BEGIN
                    INSERT INTO test_write_log (stage, offer_id, txid) VALUES ('REASON', NEW.offer_id, txid_current());
                    RETURN NEW;
                END
                $$ LANGUAGE plpgsql
                """);
        jdbc.execute("""
                CREATE OR REPLACE TRIGGER test_log_reason AFTER INSERT ON offer_score_reason FOR EACH ROW
                EXECUTE FUNCTION test_log_reason()
                """);
        jdbc.execute("""
                CREATE OR REPLACE TRIGGER test_log_content AFTER UPDATE ON offer FOR EACH ROW
                WHEN (NEW.content_at IS DISTINCT FROM OLD.content_at) EXECUTE FUNCTION test_log_write('CONTENT')
                """);
        clear();
        sourceId =
                jdbc.queryForObject("INSERT INTO source (name, kind) VALUES ('test', 'file') RETURNING id", Long.class);
    }

    @AfterEach
    void sequentialAgain() {
        width(1);
    }

    // ---- ISC-363 --------------------------------------------------------------------------

    @Test
    void scoreAtWidthFourFinishesInUnderHalfTheSequentialTime() {
        answers(JUDGED);

        Duration sequential = timed(1, () -> scoring.run());
        List<Map<String, Object>> sequentialRows = rows(SCORE_ROWS);
        MODEL.verify(ADVERTS, postRequestedFor(urlPathEqualTo("/chat/completions")));

        MODEL.resetRequests();
        Duration parallel = timed(4, () -> scoring.run());
        List<Map<String, Object>> parallelRows = rows(SCORE_ROWS);

        assertThat(sequential).isGreaterThanOrEqualTo(Duration.ofMillis(1600));
        assertThat(parallel).isLessThan(Duration.ofMillis(800));
        assertThat(parallelRows).isEqualTo(sequentialRows);
        assertThat(parallelRows)
                .allSatisfy(row -> assertThat(row.get("score_model")).isEqualTo("test-model"));
        MODEL.verify(ADVERTS, postRequestedFor(urlPathEqualTo("/chat/completions")));
        assertThat(writesPerAdvert("SCORE")).containsOnly(1);
        assertOneTransactionPerAdvert();
    }

    @Test
    void fieldsAtWidthFourFinishesInUnderHalfTheSequentialTime() {
        answers(FIELDS_READ);

        Duration sequential = timed(1, () -> fields.run());
        List<Map<String, Object>> sequentialRows = rows(FIELDS_ROWS);

        Duration parallel = timed(4, () -> fields.run());
        List<Map<String, Object>> parallelRows = rows(FIELDS_ROWS);

        assertThat(sequential).isGreaterThanOrEqualTo(Duration.ofMillis(1600));
        assertThat(parallel).isLessThan(Duration.ofMillis(800));
        assertThat(parallelRows).isEqualTo(sequentialRows);
        assertThat(parallelRows)
                .allSatisfy(row -> assertThat(row.get("start_text")).isEqualTo("ab sofort"));
        assertThat(writesPerAdvert("FIELDS")).containsOnly(1);
    }

    @Test
    void contentAtWidthFourFinishesInUnderHalfTheSequentialTime() {
        answers(NOTHING_IS_CHROME);

        Duration sequential = timed(1, () -> content.run());
        List<Map<String, Object>> sequentialRows = rows(CONTENT_ROWS);

        Duration parallel = timed(4, () -> content.run());
        List<Map<String, Object>> parallelRows = rows(CONTENT_ROWS);

        assertThat(sequential).isGreaterThanOrEqualTo(Duration.ofMillis(1600));
        assertThat(parallel).isLessThan(Duration.ofMillis(800));
        assertThat(parallelRows).isEqualTo(sequentialRows);
        assertThat(parallelRows)
                .allSatisfy(row -> assertThat(row.get("settled")).isEqualTo(true));
        assertThat(writesPerAdvert("CONTENT")).containsOnly(1);
    }

    // ---- ISC-366 --------------------------------------------------------------------------

    @Test
    void scoreUnderABudgetOfFiveAsksFiveTimesAtWidthEight() {
        answers(JUDGED);
        adverts();
        callsLeft(5);
        width(8);

        scoring.run();

        MODEL.verify(5, postRequestedFor(urlPathEqualTo("/chat/completions")));
        assertThat(count("score_model IS NOT NULL")).isEqualTo(5);
        assertThat(count("score_model IS NULL")).isEqualTo(3);
        assertThat(writesPerAdvert("SCORE"))
                .allSatisfy(writes -> assertThat(writes).isEqualTo(1));
    }

    @Test
    void fieldsUnderABudgetOfFiveStopsAndKeepsTheAnswersInFlight() {
        // Width 4: the refusal arrives while other adverts are still waiting on the model, and
        // those answers are written rather than dropped with the stop.
        answers(FIELDS_READ);
        adverts();
        callsLeft(5);
        width(4);

        fields.run();

        MODEL.verify(5, postRequestedFor(urlPathEqualTo("/chat/completions")));
        assertThat(count("fields_at IS NOT NULL")).isEqualTo(5);
        assertThat(count("fields_at IS NULL")).isEqualTo(3);
        assertThat(writesPerAdvert("FIELDS")).hasSize(5).containsOnly(1);
    }

    @Test
    void contentUnderABudgetOfFiveAsksFiveTimesAtWidthEight() {
        answers(NOTHING_IS_CHROME);
        adverts();
        callsLeft(5);
        width(8);

        var report = content.run();

        MODEL.verify(5, postRequestedFor(urlPathEqualTo("/chat/completions")));
        assertThat(count("content_at IS NOT NULL")).isEqualTo(5);
        // The three the budget refused never reached the model, so the report must not count them.
        assertThat(report.requests()).isEqualTo(5);
        assertThat(count("content_at IS NULL")).isEqualTo(3);
        assertThat(writesPerAdvert("CONTENT")).hasSize(5).containsOnly(1);
    }

    // ---- fixture --------------------------------------------------------------------------

    private static final String SCORE_ROWS =
            "SELECT external_id, score_value, score_band, score_model, ruleset_version FROM offer ORDER BY external_id";
    private static final String FIELDS_ROWS = """
            SELECT external_id, start_text, starts_on, duration, duration_months, apply_by_text, apply_by,
                   fields_model, score_model
            FROM offer ORDER BY external_id
            """;
    private static final String CONTENT_ROWS = """
            SELECT external_id, content_blocks::text AS blocks, content_model, content_undecided,
                   content_at IS NOT NULL AS settled
            FROM offer ORDER BY external_id
            """;

    /** Fresh adverts, the width, one run of the stage — timed around the run alone. */
    private Duration timed(int width, Runnable stage) {
        clear();
        sourceId =
                jdbc.queryForObject("INSERT INTO source (name, kind) VALUES ('test', 'file') RETURNING id", Long.class);
        adverts();
        width(width);
        long started = System.nanoTime();
        stage.run();
        return Duration.ofNanos(System.nanoTime() - started);
    }

    private List<Map<String, Object>> rows(String sql) {
        return jdbc.queryForList(sql);
    }

    /** How often each advert was written by the stage, one entry per advert written at all. */
    private List<Integer> writesPerAdvert(String stage) {
        return jdbc.queryForList(
                "SELECT count(*)::int FROM test_write_log WHERE stage = ? GROUP BY offer_id", Integer.class, stage);
    }

    /**
     * ISC-367: every advert SCORE wrote at width 4 was committed in a transaction of its own, and
     * that advert's reasons in the same one — the per-offer boundary {@code ScoreWriter.write}
     * draws, observed at runtime rather than read off its annotation.
     */
    private void assertOneTransactionPerAdvert() {
        List<Map<String, Object>> perAdvert = jdbc.queryForList("""
                SELECT s.offer_id, s.txid,
                       (SELECT count(*) FROM test_write_log r WHERE r.stage = 'REASON' AND r.offer_id = s.offer_id)
                           AS reasons,
                       (SELECT count(*) FROM test_write_log r
                        WHERE r.stage = 'REASON' AND r.offer_id = s.offer_id AND r.txid <> s.txid) AS elsewhere
                FROM test_write_log s WHERE s.stage = 'SCORE'
                """);
        assertThat(perAdvert).hasSize(ADVERTS);
        assertThat(perAdvert).extracting(row -> row.get("txid")).doesNotHaveDuplicates();
        assertThat(perAdvert).allSatisfy(row -> {
            assertThat(((Number) row.get("reasons")).longValue()).isPositive();
            assertThat(((Number) row.get("elsewhere")).longValue()).isZero();
        });
    }

    /** The condition is one of this class's own literals, never input. */
    private int count(String condition) {
        return jdbc.queryForObject("SELECT count(*) FROM offer WHERE " + condition, Integer.class);
    }

    private void clear() {
        jdbc.update("DELETE FROM test_write_log");
        jdbc.update("DELETE FROM offer_score_reason");
        jdbc.update("DELETE FROM content_block_label");
        jdbc.update("DELETE FROM offer");
        jdbc.update("DELETE FROM source");
        jdbc.update("DELETE FROM llm_call_budget");
    }

    /**
     * Eight due adverts with two paragraphs of their own each, so no shipped rule and no cached
     * label decides a block and every advert costs exactly one request in every stage.
     */
    private void adverts() {
        for (int index = 0; index < ADVERTS; index++) {
            jdbc.update(
                    """
                    INSERT INTO offer (source_id, external_id, title, description, url, portal, fingerprint,
                                       status, full_text)
                    VALUES (?, ?, ?, 'Kurzbeschreibung.', ?, 'portal-a', ?, 'PASSED', ?)
                    """,
                    sourceId,
                    "ext-" + index,
                    "Java-Entwickler " + index,
                    "https://example.invalid/" + index,
                    "java-entwickler-" + index,
                    "Wir suchen für Projekt Nummer %d einen erfahrenen Java-Entwickler.\n\n".formatted(index)
                            + "Das Team Nummer %d arbeitet mit Spring Boot und Kafka.".formatted(index));
        }
    }

    /** The loaded ceiling less {@code left}, so the fixture follows the shipped default. */
    private void callsLeft(int left) {
        int limit = config.snapshot().application().llm().budget().maxCallsPerDay();
        jdbc.update("INSERT INTO llm_call_budget (day, calls) VALUES (current_date, ?)", limit - left);
    }

    /** Rewrites the fixture's width and reloads it; every stage reads the snapshot per run. */
    private void width(int width) {
        try {
            Files.writeString(
                    CONFIG.resolve("pipeline.yaml"),
                    pipelineWithOpenWidth.replace(WIDTH_MARKER, String.valueOf(width)),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        config.reload();
        assertThat(config.snapshot().application().llm().concurrency()).isEqualTo(width);
    }

    /** A complete chat-completion envelope carrying {@code answer}, sent after {@link #DELAY_MS}. */
    private void answers(String answer) {
        try {
            String body = JSON.writeValueAsString(Map.of(
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
            MODEL.stubFor(post(urlPathEqualTo("/chat/completions"))
                    .willReturn(aResponse()
                            .withHeader("Content-Type", "application/json")
                            .withFixedDelay(DELAY_MS)
                            .withBody(body)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The shipped defaults with the llm block pointed at the stub and every other placeholder
     * closed the way a test context closes it. {@code content} and {@code fields} are left
     * empty, so all three stages ask {@code scoring}'s model on the one stub.
     */
    private static Path configPointingAtTheStub() {
        try {
            Path dir = Files.createTempDirectory("leadgen-concurrent-stages");
            dir.toFile().deleteOnExit();
            ConfigFixtures.materialize(dir);

            Path pipeline = dir.resolve("pipeline.yaml");
            String text = Files.readString(pipeline, StandardCharsets.UTF_8);
            text = set(text, "provider", "openai-compatible");
            text = set(text, "base_url", MODEL.baseUrl());
            text = set(text, "api_key", "test-key");
            text = set(text, "scoring", "test-model");
            // By placeholder, not by key: `concurrency:` also names the fetch width.
            String placeholder = "${LLM_CONCURRENCY:1}";
            if (!text.contains(placeholder)) {
                throw new IllegalStateException("no `" + placeholder + "` in the shipped pipeline.yaml");
            }
            text = text.replace(placeholder, WIDTH_MARKER);
            text = ConfigFixtures.closePlaceholders(text);
            pipelineWithOpenWidth = text;
            Files.writeString(pipeline, text.replace(WIDTH_MARKER, "1"), StandardCharsets.UTF_8);
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
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
