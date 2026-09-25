/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.fields;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import de.codeministry.leadgen.Databases;
import de.codeministry.leadgen.config.ConfigFixtures;
import de.codeministry.leadgen.config.ConfigRegistry;
import de.codeministry.leadgen.config.ConfigSnapshot;
import de.codeministry.leadgen.config.model.PipelineConfig;
import de.codeministry.leadgen.llm.ChatModels;
import de.codeministry.leadgen.llm.LlmBudget;
import de.codeministry.leadgen.llm.ModelChoice;
import java.time.LocalDate;
import java.util.Optional;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * What the stage writes, what it leaves due, and what it makes scoring read again.
 *
 * <p>The extractor itself is stubbed: what it sends and what it reads back is
 * {@code FieldExtractorWireFormatTest}'s question, and this one is about the three things
 * around it that are easy to get wrong in silence — the stamp, the self-healing due query,
 * and the re-judge.
 */
@SpringBootTest
@Testcontainers
class FieldsServiceTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = Databases.postgres();

    @DynamicPropertySource
    static void configuration(DynamicPropertyRegistry registry) {
        registry.add(
                "leadgen.config-dir", () -> ConfigFixtures.shippedDefaults().toString());
    }

    /**
     * One endpoint for every model, because the configuration has one {@code base_url}: "another
     * model" is another model name on it. Started in a static initialiser for the reason
     * {@code FieldExtractorWireFormatTest} gives.
     */
    private static final WireMockServer MODEL;

    static {
        MODEL = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        MODEL.start();
    }

    @Autowired
    private FieldsService fields;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ConfigRegistry config;

    @Autowired
    private DataSource dataSource;

    private static final String SCORING = "model-scoring";

    @MockitoBean
    private FieldExtractors extractors;

    @MockitoBean
    private FieldExtractor extractor;

    private long sourceId;

    @AfterAll
    static void stop() {
        MODEL.stop();
    }

    @BeforeEach
    void reset() {
        MODEL.resetAll();
        jdbc.update("DELETE FROM offer_score_reason");
        jdbc.update("DELETE FROM application_event");
        jdbc.update("DELETE FROM application");
        jdbc.update("DELETE FROM offer");
        jdbc.update("DELETE FROM source");
        sourceId =
                jdbc.queryForObject("INSERT INTO source (name, kind) VALUES ('test', 'file') RETURNING id", Long.class);
        given(extractor.model()).willReturn("a-model");
        given(extractors.current()).willReturn(Optional.of(extractor));
    }

    @Test
    void skipsWithoutAModelAndLeavesWhatThePatternsFound() {
        // Rules before model: without one the columns keep whatever the enrichment patterns
        // wrote, which is less than this stage would find and is not nothing. And nothing is
        // stamped, so the standing backlog becomes due the moment a key is configured — the
        // self-healing shape `score_model IS NULL` and `content_model IS NULL` already use.
        given(extractors.current()).willReturn(Optional.empty());
        long id = passed("Java Entwickler", LocalDate.of(2026, 10, 1), "6");

        assertThat(fields.run().considered()).isZero();

        assertThat(jdbc.queryForObject("SELECT duration FROM offer WHERE id = ?", String.class, id))
                .isEqualTo("6");
        assertThat(jdbc.queryForObject("SELECT fields_at FROM offer WHERE id = ?", Object.class, id))
                .isNull();
    }

    @Test
    void writesBothHalvesOfEveryPairAndStampsTheModel() {
        long id = passed("Java Entwickler", null, null);
        answers(new ExtractedFields(
                "ab sofort", null, "6 Monate mit Option", 6, "Bewerbungen bis 30.09.2026", LocalDate.of(2026, 9, 30)));

        var report = fields.run();

        assertThat(report.considered()).isEqualTo(1);
        assertThat(report.extracted()).isEqualTo(1);
        assertThat(report.stated()).isEqualTo(1);
        var row = jdbc.queryForMap(
                "SELECT start_text, starts_on, duration, duration_months, apply_by, apply_by_text, fields_model"
                        + " FROM offer WHERE id = ?",
                id);
        assertThat(row.get("start_text")).isEqualTo("ab sofort");
        assertThat(row.get("starts_on")).isNull();
        assertThat(row.get("duration")).isEqualTo("6 Monate mit Option");
        assertThat(row.get("duration_months")).isEqualTo(6);
        assertThat(row.get("apply_by")).hasToString("2026-09-30");
        assertThat(row.get("fields_model")).isEqualTo("a-model");
    }

    @Test
    void makesScoringReadAgainWhenAValueActuallyChanged() {
        // Nulling `score_model` is the existing self-healing mechanism, not a fourth
        // staleness criterion invented here.
        long id = scored(passed("Ohne Angaben", null, null));
        answers(new ExtractedFields("ab sofort", null, null, null, null, null));

        assertThat(fields.run().rejudged()).isEqualTo(1);
        assertThat(scoreModelOf(id)).isNull();
    }

    @Test
    void leavesTheScoreAloneWhenTheExtractorOnlyConfirmsWhatWasAlreadyThere() {
        // A re-judge is a language-model call somebody pays for later, so it is bought only
        // when something moved. An advert the extractor merely confirms costs nothing.
        long id = scored(passed("Mit Angaben", LocalDate.of(2026, 10, 1), "6"));
        answers(new ExtractedFields(null, LocalDate.of(2026, 10, 1), "6", null, null, null));

        assertThat(fields.run().rejudged()).isZero();
        assertThat(scoreModelOf(id)).isEqualTo("a-judge");
    }

    @Test
    void leavesAnOfferDueWhenAConfiguredModelDidNotAnswer() {
        // The difference between "the advert states nothing" and "nothing came back". One is
        // a finished decision, the other has to come round again.
        long id = passed("Java Entwickler", null, null);
        given(extractor.extract(any())).willReturn(Optional.empty());

        var report = fields.run();

        assertThat(report.requests()).isEqualTo(1);
        assertThat(report.extracted()).isZero();
        assertThat(jdbc.queryForObject("SELECT fields_at FROM offer WHERE id = ?", Object.class, id))
                .isNull();
        // And it is handed to the next pass rather than remembered as read by nobody.
        answers(ExtractedFields.none());
        assertThat(fields.run().considered()).isEqualTo(1);
    }

    @Test
    void doesNotReadTheSameOfferTwiceUnderTheSameModel() {
        passed("Java Entwickler", null, null);
        answers(ExtractedFields.none());

        assertThat(fields.run().considered()).isEqualTo(1);
        assertThat(fields.run().considered()).isZero();
    }

    @Test
    void runForExtractsExactlyThatOneOfferAndLeavesASecondDueOfferUntouched() {
        // The orchestrator calls this after a successful manual fetch, for one id — the rest
        // of the standing backlog must not move just because one offer was pressed.
        long target = passed("Java Entwickler", null, null);
        long other = passed("Python Entwickler", null, null);
        answers(new ExtractedFields(
                "ab sofort", null, "6 Monate mit Option", 6, "Bewerbungen bis 30.09.2026", LocalDate.of(2026, 9, 30)));

        boolean extracted = fields.runFor(target);

        assertThat(extracted).isTrue();
        var row = jdbc.queryForMap("SELECT start_text, duration, fields_model FROM offer WHERE id = ?", target);
        assertThat(row.get("start_text")).isEqualTo("ab sofort");
        assertThat(row.get("duration")).isEqualTo("6 Monate mit Option");
        assertThat(row.get("fields_model")).isEqualTo("a-model");

        assertThat(jdbc.queryForObject("SELECT fields_at FROM offer WHERE id = ?", Object.class, other))
                .isNull();
        assertThat(jdbc.queryForObject("SELECT duration FROM offer WHERE id = ?", String.class, other))
                .isNull();
    }

    @Test
    void runForDoesNothingWithoutAModelConfigured() {
        // Rules before model, same as run(): a stage that cannot run is skipped exactly as at
        // night, never an exception the orchestrator would have to catch.
        given(extractors.current()).willReturn(Optional.empty());
        long id = passed("Java Entwickler", null, null);

        assertThat(fields.runFor(id)).isFalse();
        assertThat(jdbc.queryForObject("SELECT fields_at FROM offer WHERE id = ?", Object.class, id))
                .isNull();
    }

    /** ISC-369: a switched key, an identical answer — due again, re-stamped, the score stands. */
    @Test
    void aSwitchedFieldsKeyMakesTheOfferDueAgainAndKeepsTheScoreWhenNothingMoved() {
        long id = passed("Java Entwickler", null, null);
        var answer = new ExtractedFields("ab 01.10.2026", LocalDate.of(2026, 10, 1), "6 Monate", 6, null, null);
        answers(answer);
        assertThat(keyed("a-model", SCORING).run().extracted()).isEqualTo(1);
        scored(id);

        // The same key again: nothing is due.
        assertThat(keyed("a-model", SCORING).run().considered()).isZero();

        var report = keyed("b-model", SCORING).run();

        assertThat(report.considered()).isEqualTo(1);
        assertThat(report.rejudged()).isZero();
        assertThat(fieldsModelOf(id)).isEqualTo("b-model");
        assertThat(scoreModelOf(id)).isEqualTo("a-judge");
        // And under the new key it is settled.
        assertThat(keyed("b-model", SCORING).run().considered()).isZero();
    }

    /** ISC-369: a switched key whose answer moved a value scoring reads makes scoring read again. */
    @Test
    void aSwitchedFieldsKeyWhoseAnswerMovedAValueMakesScoringReadAgain() {
        long id = passed("Java Entwickler", null, null);
        answers(new ExtractedFields("ab 01.10.2026", LocalDate.of(2026, 10, 1), "6 Monate", 6, null, null));
        keyed("a-model", SCORING).run();
        scored(id);

        answers(new ExtractedFields("ab 01.11.2026", LocalDate.of(2026, 11, 1), "6 Monate", 6, null, null));
        var report = keyed("b-model", SCORING).run();

        assertThat(report.considered()).isEqualTo(1);
        assertThat(report.rejudged()).isEqualTo(1);
        assertThat(fieldsModelOf(id)).isEqualTo("b-model");
        assertThat(scoreModelOf(id)).isNull();
    }

    @Test
    void theButtonReadsTheSameSwitchedKeyPredicateAsTheNight() {
        long id = passed("Java Entwickler", null, null);
        answers(ExtractedFields.none());
        keyed("a-model", SCORING).run();

        assertThat(keyed("a-model", SCORING).runFor(id)).isFalse();

        assertThat(keyed("b-model", SCORING).runFor(id)).isTrue();
        assertThat(fieldsModelOf(id)).isEqualTo("b-model");
    }

    /**
     * ISC-369, the blank-key half. With {@code fields} empty the extractor asks the scoring
     * model, but the stage has no key of its own to switch: a changed {@code scoring} moves the
     * judge and must not re-read every advert, at night or from the button.
     */
    @Test
    void aSwitchedScoringKeyLeavesAnAdvertReadUnderTheFallbackAlone() {
        long id = passed("Java Entwickler", null, null);
        answers(ExtractedFields.none());
        assertThat(keyed(null, "scoring-a").run().considered()).isEqualTo(1);
        assertThat(fieldsModelOf(id)).isEqualTo("scoring-a");

        assertThat(keyed(null, "scoring-b").run().considered()).isZero();
        assertThat(keyed(null, "scoring-b").runFor(id)).isFalse();
        assertThat(fieldsModelOf(id)).isEqualTo("scoring-a");
    }

    /**
     * The service as the context builds it, except that the configuration names {@code fields}
     * and {@code scoring} as given, and the mocked extractor asks the model {@link ModelChoice}
     * picks from them — the one the real {@link FieldExtractors} would build.
     */
    private FieldsService keyed(String fieldsKey, String scoringKey) {
        var models = new PipelineConfig.Llm.Models(null, scoringKey, null, null, null, null, fieldsKey);
        var llm = new PipelineConfig.Llm(
                ChatModels.OPENAI_COMPATIBLE, MODEL.baseUrl(), "test-key", null, false, models, null);
        var registry = Mockito.mock(ConfigRegistry.class);
        var snapshot = Mockito.mock(ConfigSnapshot.class);
        var pipeline = Mockito.mock(PipelineConfig.class);
        given(registry.snapshot()).willReturn(snapshot);
        given(snapshot.application()).willReturn(pipeline);
        given(pipeline.llm()).willReturn(llm);
        given(pipeline.fields()).willReturn(config.snapshot().application().fields());
        given(extractor.model()).willReturn(ModelChoice.fields(models).orElseThrow());
        return new FieldsService(
                registry,
                extractors,
                new LlmBudget(registry, JdbcClient.create(dataSource)),
                JdbcClient.create(dataSource));
    }

    /**
     * ISC-370. The fields model does not answer; the scoring model, on the same endpoint, would.
     * The real {@link FieldExtractors} builds the extractor here, against WireMock, so the
     * property pinned is the whole route: no second model is asked, and the offer stays due.
     */
    @Test
    void neverAsksTheScoringModelWhenTheFieldsModelDoesNotAnswer() {
        long id = passed("Java Entwickler", null, null);
        var real = new FieldExtractors(registryWith("model-fields"), new ChatModels());
        given(extractors.current()).willAnswer(invocation -> real.current());
        MODEL.stubFor(post(urlPathEqualTo("/chat/completions"))
                .withRequestBody(matchingJsonPath("$.model", equalTo("model-fields")))
                .willReturn(aResponse().withStatus(503).withBody("{}")));
        MODEL.stubFor(post(urlPathEqualTo("/chat/completions"))
                .withRequestBody(matchingJsonPath("$.model", equalTo("model-scoring")))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                        {"id":"chat-1","object":"chat.completion","created":1,"model":"model-scoring",
                         "choices":[{"index":0,"finish_reason":"stop",
                                     "message":{"role":"assistant","content":"{}"}}]}
                        """)));

        var report = fields.run();

        assertThat(report.requests()).isEqualTo(1);
        assertThat(report.extracted()).isZero();
        MODEL.verify(
                1,
                postRequestedFor(urlPathEqualTo("/chat/completions"))
                        .withRequestBody(matchingJsonPath("$.model", equalTo("model-fields"))));
        MODEL.verify(
                0,
                postRequestedFor(urlPathEqualTo("/chat/completions"))
                        .withRequestBody(matchingJsonPath("$.model", equalTo("model-scoring"))));
        assertThat(jdbc.queryForObject("SELECT fields_at FROM offer WHERE id = ?", Object.class, id))
                .isNull();
        assertThat(fieldsModelOf(id)).isNull();

        // Still due, and the next run asks the fields model again — still nobody else.
        assertThat(fields.run().considered()).isEqualTo(1);
        MODEL.verify(
                0,
                postRequestedFor(urlPathEqualTo("/chat/completions"))
                        .withRequestBody(matchingJsonPath("$.model", equalTo("model-scoring"))));
    }

    private static ConfigRegistry registryWith(String fieldsModel) {
        var llm = new PipelineConfig.Llm(
                ChatModels.OPENAI_COMPATIBLE,
                MODEL.baseUrl(),
                "test-key",
                null,
                false,
                new PipelineConfig.Llm.Models(null, "model-scoring", null, null, null, null, fieldsModel),
                null);
        var registry = Mockito.mock(ConfigRegistry.class);
        var snapshot = Mockito.mock(ConfigSnapshot.class);
        var pipeline = Mockito.mock(PipelineConfig.class);
        given(registry.snapshot()).willReturn(snapshot);
        given(snapshot.application()).willReturn(pipeline);
        given(pipeline.llm()).willReturn(llm);
        return registry;
    }

    private String fieldsModelOf(long id) {
        return jdbc.queryForObject("SELECT fields_model FROM offer WHERE id = ?", String.class, id);
    }

    private void answers(ExtractedFields answer) {
        given(extractor.extract(any())).willReturn(Optional.of(answer));
    }

    private String scoreModelOf(long id) {
        return jdbc.queryForObject("SELECT score_model FROM offer WHERE id = ?", String.class, id);
    }

    private long scored(long id) {
        jdbc.update(
                "UPDATE offer SET score_value = 80, score_band = 'REVIEW', score_model = 'a-judge' WHERE id = ?", id);
        return id;
    }

    private long passed(String title, LocalDate startsOn, String duration) {
        return jdbc.queryForObject(
                """
                INSERT INTO offer (source_id, external_id, title, description, url, fingerprint, status,
                                   full_text, starts_on, duration)
                VALUES (?, ?, ?, 'Kurzbeschreibung.', 'https://example.invalid/1', ?, 'PASSED',
                        'Wir suchen ab sofort.', ?, ?)
                RETURNING id
                """, Long.class, sourceId, title + System.nanoTime(), title, title.toLowerCase(), startsOn, duration);
    }
}
