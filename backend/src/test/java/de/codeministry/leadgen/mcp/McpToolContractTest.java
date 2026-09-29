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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The six tools that moved from codeministry-mcp keep their contract (spec 023, ISC-482): their
 * entries in {@code tools/list} equal the moved {@code baseline-tools.json}, and each answer has the
 * shape codeministry-mcp gave on the demo corpus, recorded under {@code mcp/recorded/}. The baseline
 * also pins the four chat tools added beside them (ISC-484), captured from this server when they
 * were written, so a changed schema of any of the ten is a reviewed diff of that file.
 *
 * <p>Shape, not values: the recording ran on the demo corpus and this test on a few seeded rows. A
 * field the recording never filled (null) says nothing about its type and is skipped; every field
 * this answer fills has to exist in the recording with the same JSON type, all the way down; and
 * every top-level field of the recording has to be in the answer. A renamed field, a changed type or
 * a dropped top-level key fails; a field only the seeded data leaves empty does not.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class McpToolContractTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /**
     * Fields codeministry-mcp emitted but the demo recording never filled: its {@code OfferProjection}
     * put the rate, the remote share and the deadline whenever the offer carried one, and no demo advert
     * did. Named here so the gap stays a known one instead of loosening the comparison for every field.
     */
    private static final Set<String> NOT_FILLED_IN_RECORDING = Set.of("rateEur", "remotePercent", "applyBy");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = Databases.postgres();

    @Autowired
    private JdbcTemplate jdbc;

    @LocalServerPort
    private int port;

    private long offerId;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM application_event");
        jdbc.update("DELETE FROM application");
        jdbc.update("DELETE FROM offer_score_reason");
        jdbc.update("DELETE FROM offer");
        jdbc.update("DELETE FROM pipeline_run");
        jdbc.update("DELETE FROM source");
        long source =
                jdbc.queryForObject("INSERT INTO source (name, kind) VALUES ('test', 'file') RETURNING id", Long.class);
        // Six offers, each with an application: one more than the answers below ask for, so both
        // paged tools report `truncated` and the search its `nextCursor`, as in the recordings.
        for (int i = 6; i >= 1; i--) {
            long id = jdbc.queryForObject("""
                    INSERT INTO offer (source_id, external_id, title, description, url, fingerprint, status,
                                       score_value, score_band, location, portal, agency, tags, rate_eur,
                                       remote_percent, duration_months, start_text, apply_by, published_on,
                                       full_text, package_dir)
                    VALUES (?, ?, 'Java Developer', 'A project', 'https://example.invalid/1', ?, 'PASSED',
                            80 + ?, 'shortlist', 'Remote', 'portal-a', 'Acme Consulting GmbH', '{java,spring}', 95,
                            100, 9, 'at once', DATE '2099-01-31', DATE '2026-09-01', 'The whole advert.', '/packages/x')
                    RETURNING id
                    """, Long.class, source, "ext-" + i, "fp-" + i, i);
            jdbc.update(
                    "INSERT INTO offer_score_reason (offer_id, factor, label, points, position) VALUES (?, 'core_skill_overlap', 'Java', 40, 0)",
                    id);
            jdbc.update(
                    "INSERT INTO application (offer_id, status, sent_on) VALUES (?, 'SENT', DATE '2026-09-10')", id);
            jdbc.update("UPDATE offer SET content_blocks = CAST(? AS jsonb) WHERE id = ?", """
                    [{"index": 0, "kind": "CONTENT", "text": "The whole advert.", "reason": "fixture", "by": "RULE"}]""", id);
            offerId = id;
        }
        jdbc.update("""
                INSERT INTO pipeline_run (
                    started_at, finished_at, ruleset_version, score_model, status,
                    documents, extracted, written, merged, filter_considered, filter_passed,
                    enrich_considered, enriched, incomplete, from_cache, requests,
                    score_considered, scored, unscored, shortlisted, review, submitted, packaged, digest_written)
                VALUES (now() - interval '1 hour', now(), '1', 'a-model', 'COMPLETE',
                        1, 1, 1, 0, 1, 1, 0, 0, 0, 0, 0, 1, 1, 0, 1, 0, 0, 0, true)
                """);
    }

    @Test
    void theToolListEqualsTheBaseline() {
        var tools = sorted(client().tools());
        assertThat(tools).isEqualTo(sorted(resource("mcp/baseline-tools.json")));
    }

    @Test
    void toolParametersKeepTheirDeclaredNames() {
        for (JsonNode tool : client().tools()) {
            assertThat(tool.path("inputSchema").path("properties").propertyNames())
                    .as("parameter names of %s", tool.path("name").asString())
                    .noneMatch(name -> name.matches("arg\\d+"));
        }
    }

    @Test
    void searchOffersAnswersInTheRecordedShape() {
        assertSameShape("leadgen_search_offers", "{\"limit\":3}", "leadgen_search_offers");
    }

    @Test
    void getOfferAnswersInTheRecordedShape() {
        var answer = assertSameShape("leadgen_get_offer", "{\"id\":" + offerId + "}", "leadgen_get_offer");
        assertThat(answer.path("offer").path("fullText").isNull())
                .as("no advert text unless asked")
                .isTrue();
        // Nor the same text again as the blocks the detail reads it in.
        assertThat(answer.path("content")).as("no advert blocks unless asked").isEmpty();
        // A server path is nothing a client can use; the board says hasPackage instead.
        assertThat(noValue(answer.path("offer").path("packageDir")))
                .as("no package path")
                .isTrue();
        var whole =
                client().callToolJson("leadgen_get_offer", "{\"id\":%d,\"includeFullText\":true}".formatted(offerId));
        assertThat(whole.path("offer").path("fullText").asString()).isEqualTo("The whole advert.");
        assertThat(whole.path("content").path(0).path("text").asString()).isEqualTo("The whole advert.");
        assertThat(noValue(whole.path("offer").path("packageDir")))
                .as("no package path, even in full")
                .isTrue();
    }

    @Test
    void funnelStatsAnswersInTheRecordedShape() {
        assertSameShape("leadgen_funnel_stats", "{}", "leadgen_funnel_stats");
        assertSameShape("leadgen_funnel_stats", "{\"section\":\"market\"}", "leadgen_funnel_stats-market");
    }

    @Test
    void ingestStatusAnswersInTheRecordedShape() {
        assertSameShape("leadgen_ingest_status", "{}", "leadgen_ingest_status");
    }

    @Test
    void listApplicationsAnswersInTheRecordedShape() {
        assertSameShape("leadgen_list_applications", "{\"limit\":5}", "leadgen_list_applications");
    }

    /** An unknown status is an error naming the valid ones, never an empty board read as a fact. */
    @Test
    void anUnknownStatusIsRefusedWithTheValidOnes() {
        var answer = client().callTool("leadgen_list_applications", "{\"status\":\"SUBMITTED\"}");

        assertThat(answer.path("isError").asBoolean(false)).isTrue();
        assertThat(answer.path("content").path(0).path("text").asString())
                .contains("SUBMITTED")
                .contains("SENT")
                .contains("PACKAGED");
    }

    @Test
    void pipelineConfigAnswersInTheRecordedShapeForEverySection() {
        for (String section : List.of("rules", "scoring-models", "sources", "prompts")) {
            assertSameShape(
                    "leadgen_get_pipeline_config",
                    "{\"section\":\"%s\"}".formatted(section),
                    "leadgen_get_pipeline_config-" + section);
        }
    }

    private JsonNode assertSameShape(String tool, String arguments, String recording) {
        var actual = client().callToolJson(tool, arguments);
        var recorded = resource("mcp/recorded/" + recording + ".json");
        var problems = new ArrayList<String>();
        compare(recording, recorded, actual, problems);
        if (recorded.isObject() && actual.isObject()) {
            for (String key : recorded.propertyNames()) {
                if (!recorded.get(key).isNull() && !actual.has(key)) {
                    problems.add(recording + "." + key + ": top-level field missing");
                }
            }
        }
        assertThat(problems)
                .as("%s answers in the shape codeministry-mcp gave", tool)
                .isEmpty();
        return actual;
    }

    /** Every field `actual` fills exists in `recorded` with the same JSON type, recursively. */
    private static void compare(String path, JsonNode recorded, JsonNode actual, List<String> problems) {
        if (recorded == null || recorded.isNull() || actual == null || actual.isNull()) {
            return;
        }
        if (recorded.getNodeType() != actual.getNodeType()) {
            problems.add(path + ": " + actual.getNodeType() + " where the recording has " + recorded.getNodeType());
            return;
        }
        if (actual.isObject()) {
            for (String key : actual.propertyNames()) {
                var value = actual.get(key);
                if (value.isNull()) {
                    continue;
                }
                if (!recorded.has(key) && !NOT_FILLED_IN_RECORDING.contains(key)) {
                    problems.add(path + "." + key + ": not in the recording");
                    continue;
                }
                if (recorded.has(key)) {
                    compare(path + "." + key, recorded.get(key), value, problems);
                }
            }
        } else if (actual.isArray() && !actual.isEmpty() && !recorded.isEmpty()) {
            compare(path + "[0]", recorded.get(0), actual.get(0), problems);
        }
    }

    private static boolean noValue(JsonNode node) {
        return node.isMissingNode() || node.isNull();
    }

    private McpTestClient client() {
        return McpTestClient.connect("http://localhost:" + port);
    }

    private static List<JsonNode> sorted(JsonNode tools) {
        var list = new ArrayList<JsonNode>();
        tools.forEach(list::add);
        list.sort(Comparator.comparing(tool -> tool.path("name").asString()));
        return list;
    }

    private static JsonNode resource(String path) {
        try (var in = new ClassPathResource(path).getInputStream()) {
            return JSON.readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException(path + " is missing", e);
        }
    }
}
