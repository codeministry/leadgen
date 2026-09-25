/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.content;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import de.codeministry.leadgen.Databases;
import de.codeministry.leadgen.config.ConfigFixtures;
import de.codeministry.leadgen.config.ConfigRegistry;
import de.codeministry.leadgen.config.ConfigSnapshot;
import de.codeministry.leadgen.config.model.PipelineConfig;
import de.codeministry.leadgen.llm.ChatModels;
import de.codeministry.leadgen.llm.LlmBudget;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * {@link ContentService#runFor(long)} against a real database.
 *
 * <p>Its own class rather than a corner of {@code ContentSegmentationTest}, which is a plain
 * unit test of splitting and rules and should stay one: the one thing worth proving here
 * against a fixture is that the button's predicate touches its own offer and nothing beside
 * it, and that needs the schema.
 */
@SpringBootTest
@Testcontainers
class ContentServiceTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = Databases.postgres();

    /** Created once: a {@code @DynamicPropertySource} supplier may run more than once. */
    private static final Path CONFIG = keylessDefaults();

    /**
     * One endpoint for every model, because the configuration has one {@code base_url}: "another
     * model" is another model name on the same endpoint, and that name is what the stubs below
     * tell apart. Started in a static initialiser for the reason
     * {@code ContentClassifierWireFormatTest} gives.
     */
    private static final WireMockServer MODEL;

    static {
        MODEL = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        MODEL.start();
    }

    /**
     * Three blocks no shipped rule recognises, so every one of them reaches the cache and then
     * the model — the path a switched key has to take.
     */
    private static final String THREE_BLOCKS = """
            Wir suchen eine Angular Entwicklerin fuer ein Kundenprojekt.

            Quux widget seven footer strip.

            Remote, sechs Monate, Start im Oktober.
            """;

    private static final String BLOCK_ONE_IS_CHROME =
            "{\"blocks\":[{\"index\":1,\"kind\":\"CHROME\",\"reason\":\"Furniture.\"}]}";

    private static final String NOTHING_IS_CHROME = "{\"blocks\":[]}";

    @Autowired
    private ContentService content;

    @Autowired
    private ConfigRegistry config;

    @Autowired
    private BlockLabelStore labels;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbc;

    private long sourceId;

    @DynamicPropertySource
    static void configuration(DynamicPropertyRegistry registry) {
        registry.add("leadgen.config-dir", CONFIG::toString);
    }

    @AfterAll
    static void stop() {
        MODEL.stop();
    }

    @BeforeEach
    void reset() {
        MODEL.resetAll();
        jdbc.update("DELETE FROM content_block_label");
        jdbc.update("DELETE FROM offer");
        jdbc.update("DELETE FROM source");
        sourceId =
                jdbc.queryForObject("INSERT INTO source (name, kind) VALUES ('test', 'file') RETURNING id", Long.class);
    }

    @Test
    void segmentsExactlyTheOfferItWasAskedForAndLeavesAnotherDueOfferUntouched() {
        long target = dueOffer("/projekt/target");
        long other = dueOffer("/projekt/other");

        var report = content.runFor(target);

        assertThat(report.considered()).isEqualTo(1);
        assertThat(report.segmented()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT content_at FROM offer WHERE id = ?", Timestamp.class, target))
                .isNotNull();
        assertThat(jdbc.queryForObject("SELECT content_blocks FROM offer WHERE id = ?", String.class, target))
                .isNotNull();

        // The second offer is still due: `runFor` reads the same predicate as `run()`, with
        // `id = ?` added rather than substituted for it, so a call for one offer cannot reach
        // another the night would also have picked up.
        assertThat(jdbc.queryForObject("SELECT content_at FROM offer WHERE id = ?", Timestamp.class, other))
                .isNull();
        assertThat(jdbc.queryForObject("SELECT content_blocks FROM offer WHERE id = ?", String.class, other))
                .isNull();
    }

    @Test
    void answersSkippedForAnOfferTheNightWouldNotTouch() {
        // Archived, so the predicate excludes it even though it otherwise looks due. `runFor`
        // must not force a write through a path this offer does not belong on.
        long archived = dueOffer("/projekt/archived");
        jdbc.update("UPDATE offer SET archived_at = now() WHERE id = ?", archived);

        var report = content.runFor(archived);

        assertThat(report.considered()).isZero();
        assertThat(report.segmented()).isZero();
        assertThat(jdbc.queryForObject("SELECT content_at FROM offer WHERE id = ?", Timestamp.class, archived))
                .isNull();
    }

    /**
     * ISC-369, the half where the new model agrees. Its question is only the blocks the cache
     * does not already answer, and an answer that leaves the advert reading as before costs no
     * re-judge.
     */
    @Test
    void aSwitchedContentKeyMakesTheAdvertDueAgainAndKeepsTheScoreWhenTheBlocksDidNotMove() {
        long id = threeBlockOffer();
        answersAs("model-a", BLOCK_ONE_IS_CHROME);
        assertThat(serviceWith("model-a").run().segmented()).isEqualTo(1);
        scored(id);

        // The same key again: nothing is due, so one advert is never asked about twice.
        assertThat(serviceWith("model-a").run().considered()).isZero();

        // A switched key: due again. The cached label for block 1 answers without a call, and
        // only the two blocks the cache does not know go to model-b — which agrees.
        answersAs("model-b", NOTHING_IS_CHROME);
        var report = serviceWith("model-b").run();

        assertThat(report.considered()).isEqualTo(1);
        assertThat(report.segmented()).isEqualTo(1);
        assertThat(report.requests()).isEqualTo(1);
        assertThat(contentModelOf(id)).isEqualTo("model-b");
        assertThat(jdbc.queryForObject("SELECT content_at FROM offer WHERE id = ?", Timestamp.class, id))
                .isNotNull();
        // The advert reads exactly as it did, so the judge's score stands.
        assertThat(scoreModelOf(id)).isEqualTo("a-judge");
        // And under the new key it is settled: the next run finds nothing to do.
        assertThat(serviceWith("model-b").run().considered()).isZero();
    }

    /** ISC-369, the half where the new model disagrees: what scoring reads moved. */
    @Test
    void aSwitchedContentKeyWhoseAnswerMovesTheBlocksMakesScoringReadAgain() {
        long id = threeBlockOffer();
        answersAs("model-a", BLOCK_ONE_IS_CHROME);
        serviceWith("model-a").run();
        scored(id);

        answersAs("model-b", "{\"blocks\":[{\"index\":2,\"kind\":\"CHROME\",\"reason\":\"Furniture too.\"}]}");
        var report = serviceWith("model-b").run();

        assertThat(report.considered()).isEqualTo(1);
        assertThat(contentModelOf(id)).isEqualTo("model-b");
        assertThat(scoreModelOf(id)).isNull();
    }

    @Test
    void theButtonReadsTheSameSwitchedKeyPredicateAsTheNight() {
        long id = threeBlockOffer();
        answersAs("model-a", NOTHING_IS_CHROME);
        serviceWith("model-a").run();

        assertThat(serviceWith("model-a").runFor(id).considered()).isZero();

        answersAs("model-b", NOTHING_IS_CHROME);
        assertThat(serviceWith("model-b").runFor(id).considered()).isEqualTo(1);
        assertThat(contentModelOf(id)).isEqualTo("model-b");
    }

    /**
     * ISC-369, the blank-key half. With {@code content} empty the classifier asks the scoring
     * model, but the stage has no key of its own to switch: a changed {@code scoring} moves the
     * judge and must not re-walk every segmented advert, at night or from the button.
     */
    @Test
    void aSwitchedScoringKeyLeavesAnAdvertSegmentedUnderTheFallbackAlone() {
        long id = threeBlockOffer();
        answersAs("model-a", BLOCK_ONE_IS_CHROME);
        assertThat(serviceWith(null, "model-a").run().segmented()).isEqualTo(1);
        assertThat(contentModelOf(id)).isEqualTo("model-a");

        answersAs("model-b", NOTHING_IS_CHROME);
        assertThat(serviceWith(null, "model-b").run().considered()).isZero();
        assertThat(serviceWith(null, "model-b").runFor(id).considered()).isZero();
        assertThat(contentModelOf(id)).isEqualTo("model-a");
    }

    /**
     * ISC-370. The content model does not answer; the scoring model, on the same endpoint,
     * would. The stage must not ask it instead: the question belongs to the configured key, and
     * a model that did not answer leaves the advert due rather than decided by somebody else.
     */
    @Test
    void neverAsksTheScoringModelWhenTheContentModelDoesNotAnswer() {
        long id = threeBlockOffer();
        MODEL.stubFor(post(urlPathEqualTo("/chat/completions"))
                .withRequestBody(matchingJsonPath("$.model", equalTo("model-content")))
                .willReturn(aResponse().withStatus(503).withBody("{}")));
        answersAs("model-scoring", BLOCK_ONE_IS_CHROME);

        var service = serviceWith("model-content");
        var report = service.run();

        assertThat(report.requests()).isEqualTo(1);
        assertThat(report.segmented()).isZero();
        MODEL.verify(
                1,
                postRequestedFor(urlPathEqualTo("/chat/completions"))
                        .withRequestBody(matchingJsonPath("$.model", equalTo("model-content"))));
        MODEL.verify(
                0,
                postRequestedFor(urlPathEqualTo("/chat/completions"))
                        .withRequestBody(matchingJsonPath("$.model", equalTo("model-scoring"))));
        assertThat(jdbc.queryForObject("SELECT content_at FROM offer WHERE id = ?", Timestamp.class, id))
                .isNull();
        assertThat(contentModelOf(id)).isNull();

        // Still due, and the next run asks the content model again — still nobody else.
        assertThat(service.run().considered()).isEqualTo(1);
        MODEL.verify(
                0,
                postRequestedFor(urlPathEqualTo("/chat/completions"))
                        .withRequestBody(matchingJsonPath("$.model", equalTo("model-scoring"))));
    }

    /**
     * ISC-367, the sequential half of the budget: at width 1 a spent day asks nothing and
     * decides nothing, and the advert stays due for the run after — the same outcome the width-8
     * case in {@code ConcurrentStagesTest} proves for the adverts it never handed out.
     */
    @Test
    void aSpentBudgetAtWidthOneAsksNothingAndLeavesTheAdvertDue() {
        long id = threeBlockOffer();
        answersAs("model-content", BLOCK_ONE_IS_CHROME);
        jdbc.update("DELETE FROM llm_call_budget");
        jdbc.update("INSERT INTO llm_call_budget (day, calls) VALUES (current_date, 1)");
        try {
            var service = serviceWith("model-content", "model-scoring", new PipelineConfig.Llm.Budget(1), 1);

            var report = service.run();

            assertThat(report.considered()).isEqualTo(1);
            assertThat(report.requests()).isZero();
            assertThat(report.segmented()).isZero();
            assertThat(report.width()).isEqualTo(1);
            MODEL.verify(0, postRequestedFor(urlPathEqualTo("/chat/completions")));
            assertThat(jdbc.queryForObject("SELECT content_at FROM offer WHERE id = ?", Timestamp.class, id))
                    .isNull();

            // Still due: the day turns over, and the next run picks it up and asks.
            jdbc.update("DELETE FROM llm_call_budget");
            var next = service.run();
            assertThat(next.considered()).isEqualTo(1);
            assertThat(next.requests()).isEqualTo(1);
        } finally {
            jdbc.update("DELETE FROM llm_call_budget");
        }
    }

    /**
     * The service as the context builds it, except that the configuration names {@code scoring}
     * and {@code content} on the WireMock endpoint. The content settings and their rules are the
     * shipped ones, read from the real registry.
     */
    private ContentService serviceWith(String contentModel) {
        return serviceWith(contentModel, "model-scoring");
    }

    private ContentService serviceWith(String contentModel, String scoringModel) {
        return serviceWith(contentModel, scoringModel, null, null);
    }

    private ContentService serviceWith(
            String contentModel, String scoringModel, PipelineConfig.Llm.Budget budget, Integer width) {
        var llm = new PipelineConfig.Llm(
                ChatModels.OPENAI_COMPATIBLE,
                MODEL.baseUrl(),
                "test-key",
                null,
                false,
                new PipelineConfig.Llm.Models(null, scoringModel, null, null, null, contentModel, null),
                budget,
                width);
        var registry = Mockito.mock(ConfigRegistry.class);
        var snapshot = Mockito.mock(ConfigSnapshot.class);
        var pipeline = Mockito.mock(PipelineConfig.class);
        given(registry.snapshot()).willReturn(snapshot);
        given(snapshot.application()).willReturn(pipeline);
        given(pipeline.llm()).willReturn(llm);
        given(pipeline.content()).willReturn(config.snapshot().application().content());
        return new ContentService(
                registry,
                new Classifiers(registry, new ChatModels()),
                labels,
                new LlmBudget(registry, JdbcClient.create(dataSource)),
                dataSource);
    }

    /** A whole chat completion, answered only to a request that names {@code model}. */
    private static void answersAs(String model, String answer) {
        MODEL.stubFor(post(urlPathEqualTo("/chat/completions"))
                .withRequestBody(matchingJsonPath("$.model", equalTo(model)))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                        {"id":"chat-1","object":"chat.completion","created":1,"model":"%s",
                         "choices":[{"index":0,"finish_reason":"stop",
                                     "message":{"role":"assistant","content":%s}}]}
                        """.formatted(
                                model, new ObjectMapper().valueToTree(answer).toString()))));
    }

    private long threeBlockOffer() {
        assertThat(MarkdownBlocks.split(THREE_BLOCKS)).hasSize(3);
        return jdbc.queryForObject("""
                INSERT INTO offer (source_id, external_id, title, url, portal, fingerprint, status, full_text)
                VALUES (?, 'three', 'Angular Entwicklerin', 'https://example.invalid/three', 'example.invalid',
                        'angular entwicklerin', 'PASSED', ?)
                RETURNING id
                """, Long.class, sourceId, THREE_BLOCKS);
    }

    private void scored(long id) {
        jdbc.update(
                "UPDATE offer SET score_value = 80, score_band = 'REVIEW', score_model = 'a-judge' WHERE id = ?", id);
    }

    private String contentModelOf(long id) {
        return jdbc.queryForObject("SELECT content_model FROM offer WHERE id = ?", String.class, id);
    }

    private String scoreModelOf(long id) {
        return jdbc.queryForObject("SELECT score_model FROM offer WHERE id = ?", String.class, id);
    }

    private long dueOffer(String path) {
        return jdbc.queryForObject(
                """
                INSERT INTO offer (source_id, external_id, title, url, portal, fingerprint, status, full_text)
                VALUES (?, ?, 'Angular Entwickler (m/w/d)', ?, 'example.invalid', 'angular entwickler', 'PASSED', ?)
                RETURNING id
                """, Long.class, sourceId, path, "https://example.invalid" + path, ContentSegmentationTest.ADVERT);
    }

    /**
     * The shipped defaults with every {@code ${LLM_*}} placeholder emptied, the guard
     * {@code ScoringWithoutAModelTest} carries: the resolver reads the developer's own
     * {@code .env} behind the process environment, and a key configured there would turn this
     * test's "no classifier" assumption into a real call against a real endpoint.
     */
    private static Path keylessDefaults() {
        try {
            Path dir = Files.createTempDirectory("leadgen-content-runfor");
            dir.toFile().deleteOnExit();
            ConfigFixtures.materialize(dir);
            Path pipeline = dir.resolve("pipeline.yaml");
            Files.writeString(pipeline, Files.readString(pipeline).replaceAll("\\$\\{LLM_[A-Z_]+(?::[^}]*)?}", "''"));
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
