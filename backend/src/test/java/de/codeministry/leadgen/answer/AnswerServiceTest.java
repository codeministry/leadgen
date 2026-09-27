/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.answer;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import de.codeministry.leadgen.Databases;
import de.codeministry.leadgen.config.ConfigFixtures;
import de.codeministry.leadgen.config.ConfigRegistry;
import de.codeministry.leadgen.content.BlockDigest;
import de.codeministry.leadgen.content.ContentClassifier;
import de.codeministry.leadgen.content.ContentKind;
import de.codeministry.leadgen.fields.FieldExtractor;
import de.codeministry.leadgen.llm.LlmBudget;
import de.codeministry.leadgen.score.ChatClientJudge;
import de.codeministry.leadgen.score.ScoreCandidate;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * ISC-371, the server half: a candidate model is asked the stage's own question, through the
 * stage's own prompt builder, and its reply is read by the stage's own reader.
 *
 * <p>The expected prompt is computed by the same public builders the stages call, never
 * copied out as a string: a copy would stay green while the stage's prompt moved on, which is
 * exactly the drift this measurement exists to rule out. The stub is a whole chat completion,
 * because a stub that is not one proves nothing about the client that reads it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class AnswerServiceTest {

    private static final WireMockServer MODEL;

    static {
        MODEL = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        MODEL.start();
    }

    /** The day's allowance in the fixture; the spent-budget test writes exactly this many. */
    private static final int ALLOWANCE = 5;

    /** How long the stub waits, so the measured latency has a floor to be checked against. */
    private static final int DELAY_MILLIS = 60;

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = Databases.postgres();

    /** Created once: a {@code @DynamicPropertySource} supplier may run more than once. */
    private static final Path CONFIG = modelAtWireMock();

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String ADVERT = "Java 21 und Spring Boot, 12 Monate, ab sofort.";
    private static final String PRIVACY = "Datenschutzhinweis: wir verarbeiten Ihre Daten.";

    @Autowired
    private AnswerService answers;

    @Autowired
    private LlmBudget budget;

    @Autowired
    private ConfigRegistry config;

    @Autowired
    private MockMvcTester mvc;

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
        jdbc.update("DELETE FROM offer_score_reason");
        jdbc.update("DELETE FROM offer");
        jdbc.update("DELETE FROM source");
        jdbc.update("DELETE FROM llm_call_budget");
        jdbc.update("DELETE FROM fetched_page");
        sourceId =
                jdbc.queryForObject("INSERT INTO source (name, kind) VALUES ('test', 'file') RETURNING id", Long.class);
        MODEL.resetAll();
    }

    @Test
    void asksTheClassifiersQuestionAboutTheBlocksAModelDecided() throws IOException {
        long id = answered();
        replies("{\"blocks\":[{\"index\":2,\"kind\":\"LEGAL\",\"reason\":\"A privacy notice.\"}]}");
        String before = rows();
        int used = budget.used();

        ModelAnswer answer = answers.answer(id, AnswerQuestion.BLOCKS, "other-model");

        // The prompt the stage would build over exactly the blocks a model decided: index 0 was
        // a rule's, so it was never asked and is not asked now.
        assertThat(system()).isEqualTo(ContentClassifier.instructions());
        assertThat(user())
                .isEqualTo(ContentClassifier.describe(
                        "Senior Java Entwickler (m/w/d)",
                        List.of(
                                new ContentClassifier.Candidate(1, BlockDigest.sample(ADVERT)),
                                new ContentClassifier.Candidate(2, BlockDigest.sample(PRIVACY)))));
        // An omitted index is the model saying "this is the advert".
        assertThat(answer.answer())
                .isEqualTo(new BlocksAnswer(
                        List.of(new LabelledBlock(1, ContentKind.CONTENT), new LabelledBlock(2, ContentKind.LEGAL))));
        assertThat(answer.answer()).isEqualTo(answer.stored());
        assertThat(answer.raw()).contains("A privacy notice.");
        assertThat(answer.model()).isEqualTo("other-model");
        assertThat(answer.millis()).isGreaterThanOrEqualTo(DELAY_MILLIS);
        assertOneCallAndNoRowChanged(used, before);
    }

    @Test
    void asksTheExtractorsQuestionAndReadsItThroughItsBounds() throws IOException {
        long id = answered();
        replies("""
                {"start":{"text":"ab sofort","date":"2026-10-01"},
                 "duration":{"text":"12 Monate","months":12},
                 "deadline":{"text":"bis gestern","date":"1970-01-01"}}
                """);
        String before = rows();
        int used = budget.used();

        ModelAnswer answer = answers.answer(id, AnswerQuestion.FIELDS, "test-model");

        assertThat(system()).isEqualTo(FieldExtractor.instructions());
        // The pattern half as the enrichment patterns read the stored text ("12" out of
        // "12 Monate", no date out of "ab sofort"), never the row's 2026-10-01 / "12 Monate",
        // which is the incumbent's own answer.
        assertThat(user())
                .isEqualTo(FieldExtractor.describe(new FieldExtractor.Candidate(
                        id, "Senior Java Entwickler (m/w/d)", "Kurzbeschreibung.", ADVERT, null, "12")));
        // The epoch date is outside the extractor's window, so its own reader drops it and
        // keeps the phrase — the same answer the stage would have written.
        assertThat(answer.answer())
                .isEqualTo(new FieldsAnswer(
                        new DatedField("ab sofort", "2026-10-01"),
                        new DurationField("12 Monate", 12),
                        new DatedField("bis gestern", null)));
        assertThat(answer.stored()).isInstanceOf(FieldsAnswer.class);
        assertThat(answer.raw()).contains("bis gestern");
        assertThat(answer.millis()).isGreaterThanOrEqualTo(DELAY_MILLIS);
        assertOneCallAndNoRowChanged(used, before);
    }

    @Test
    void givesTheExtractorWhatThePatternsReadOffThePageAndNotTheStoredAnswer() throws IOException {
        // The row's starts_on 2026-10-01 and duration "12 Monate" are the model's answer, written
        // over what the patterns had read. The fetched page is what the patterns ran on, and it
        // says something else — so the question carries the page's values, and a candidate
        // cannot agree with the incumbent by copying it out of the prompt.
        long id = answered();
        jdbc.update(
                "INSERT INTO fetched_page (url, status, body, fetched_at) VALUES (?, 200, ?, now())",
                "https://example.invalid/x",
                "<html><body><main>Projektstart ab 15.11.2026, Laufzeit 6 Monate.</main></body></html>");
        replies("{\"start\":{\"text\":null,\"date\":null},\"duration\":{\"text\":null,\"months\":null},"
                + "\"deadline\":{\"text\":null,\"date\":null}}");

        answers.answer(id, AnswerQuestion.FIELDS, "test-model");

        assertThat(user())
                .isEqualTo(FieldExtractor.describe(new FieldExtractor.Candidate(
                        id,
                        "Senior Java Entwickler (m/w/d)",
                        "Kurzbeschreibung.",
                        ADVERT,
                        LocalDate.of(2026, 11, 15),
                        "6")))
                .doesNotContain("start: 2026-10-01")
                .doesNotContain("duration: 12 Monate");
    }

    @Test
    void asksTheJudgesQuestionAndBoundsItByTheWeightTable() throws IOException {
        long id = answered();
        // 99 is above role_fit's weight, the invented factor is not one of the judge's, and the
        // topic is not a judged factor: the judge's own reader clamps and drops all three.
        replies("""
                {"reasons":[{"factor":"role_fit","label":"A backend role.","points":99},
                            {"factor":"vague_description","label":"Says little.","points":-10},
                            {"factor":"made_up","label":"Invented.","points":40}],
                 "topics":["kotlin"]}
                """);
        String before = rows();
        int used = budget.used();

        ModelAnswer answer = answers.answer(id, AnswerQuestion.JUDGE, "other-model");

        var rules = config.snapshot().rules();
        assertThat(system())
                .isEqualTo(ChatClientJudge.instructions(
                        rules == null ? null : rules.scoring(),
                        config.snapshot().profile()));
        assertThat(user()).isEqualTo(ChatClientJudge.describe(scoreCandidate(id)));
        assertThat(answer.answer())
                .isEqualTo(new JudgeAnswer(List.of(
                        new JudgedPoints("role_fit", 15),
                        new JudgedPoints("stack_mismatch_dominant", 0),
                        new JudgedPoints("role_mismatch", 0),
                        new JudgedPoints("vague_description", -10))));
        assertThat(answer.answer()).isEqualTo(answer.stored());
        assertThat(answer.raw()).contains("made_up");
        assertThat(answer.millis()).isGreaterThanOrEqualTo(DELAY_MILLIS);
        assertOneCallAndNoRowChanged(used, before);
    }

    @Test
    void scoresAReplyItCannotReadAsTheEmptyAnswerAndKeepsTheText() {
        long id = answered();
        replies("I am sorry, I cannot help with that.");

        ModelAnswer blocks = answers.answer(id, AnswerQuestion.BLOCKS, "test-model");
        ModelAnswer fields = answers.answer(id, AnswerQuestion.FIELDS, "test-model");
        ModelAnswer judge = answers.answer(id, AnswerQuestion.JUDGE, "test-model");

        // Disagreement, not an error: a smaller model's unreadable reply is what the
        // measurement has to count.
        assertThat(blocks.answer())
                .isEqualTo(new BlocksAnswer(
                        List.of(new LabelledBlock(1, ContentKind.CONTENT), new LabelledBlock(2, ContentKind.CONTENT))));
        assertThat(fields.answer())
                .isEqualTo(new FieldsAnswer(
                        new DatedField(null, null), new DurationField(null, null), new DatedField(null, null)));
        assertThat(judge.answer())
                .isEqualTo(new JudgeAnswer(List.of(
                        new JudgedPoints("role_fit", 0),
                        new JudgedPoints("stack_mismatch_dominant", 0),
                        new JudgedPoints("role_mismatch", 0),
                        new JudgedPoints("vague_description", 0))));
        assertThat(List.of(blocks.raw(), fields.raw(), judge.raw()))
                .allMatch(raw -> raw.equals("I am sorry, I cannot help with that."));
    }

    @Test
    void refusesAnUnlistedModelBeforeAnyBudgetIsSpent() {
        long id = answered();
        replies("{}");

        assertThat(mvc.post()
                        .uri("/api/v1/offers/{id}/answer", id)
                        .param("question", "judge")
                        .param("model", "somebody-elses-model"))
                .hasStatus(HttpStatus.BAD_REQUEST);

        assertThat(budget.used()).isZero();
        MODEL.verify(0, postRequestedFor(urlPathEqualTo("/chat/completions")));
    }

    @Test
    void asksAModelTheConfigurationNamesOnlyForOneStage() {
        // Once routing is adopted the incumbent of `blocks` is named under `content` and on no
        // judge list; refusing it would make the incumbent-versus-small comparison impossible.
        long id = answered();
        replies("{\"blocks\":[{\"index\":2,\"kind\":\"LEGAL\",\"reason\":\"A privacy notice.\"}]}");

        ModelAnswer answer = answers.answer(id, AnswerQuestion.BLOCKS, "content-model");

        assertThat(answer.model()).isEqualTo("content-model");
        assertThat(answer.answer()).isEqualTo(answer.stored());
        MODEL.verify(1, postRequestedFor(urlPathEqualTo("/chat/completions")));
    }

    @Test
    void namesEveryConfiguredModelWhenItRefusesOne() {
        long id = answered();
        replies("{}");

        assertThat(mvc.post()
                        .uri("/api/v1/offers/{id}/answer", id)
                        .param("question", "blocks")
                        .param("model", "somebody-elses-model"))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyText()
                .isEqualTo("model somebody-elses-model is not configured; "
                        + "the configuration names test-model, other-model, content-model");

        assertThat(budget.used()).isZero();
        MODEL.verify(0, postRequestedFor(urlPathEqualTo("/chat/completions")));
    }

    @Test
    void refusesToCompareAJudgementMadeUnderAnotherRuleset() {
        // The weight table bounds the judge's answer, so a stored judgement under version N
        // and a candidate's under version N+1 differ by the table, not by the model.
        long id = answered();
        jdbc.update("UPDATE offer SET ruleset_version = '0' WHERE id = ?", id);
        replies("{}");
        String before = rows();

        assertThat(mvc.post()
                        .uri("/api/v1/offers/{id}/answer", id)
                        .param("question", "judge")
                        .param("model", "other-model"))
                .hasStatus(HttpStatus.CONFLICT)
                .bodyText()
                .isEqualTo("offer " + id + " was judged under ruleset 0; the current ruleset is " + currentRuleset());

        assertThat(budget.used()).isZero();
        MODEL.verify(0, postRequestedFor(urlPathEqualTo("/chat/completions")));
        assertThat(rows()).isEqualTo(before);
    }

    @Test
    void refusesWith429AndAsksNothingOnceTheDayIsSpent() {
        long id = answered();
        replies("{}");
        jdbc.update("INSERT INTO llm_call_budget (day, calls) VALUES (current_date, ?)", ALLOWANCE);
        String before = rows();

        assertThat(mvc.post()
                        .uri("/api/v1/offers/{id}/answer", id)
                        .param("question", "fields")
                        .param("model", "test-model"))
                .hasStatus(HttpStatus.TOO_MANY_REQUESTS);

        MODEL.verify(0, postRequestedFor(urlPathEqualTo("/chat/completions")));
        assertThat(budget.used()).isEqualTo(ALLOWANCE);
        assertThat(rows()).isEqualTo(before);
    }

    private void assertOneCallAndNoRowChanged(int usedBefore, String rowsBefore) {
        MODEL.verify(1, postRequestedFor(urlPathEqualTo("/chat/completions")));
        assertThat(budget.used()).isEqualTo(usedBefore + 1);
        assertThat(rows()).isEqualTo(rowsBefore);
    }

    /** The row as scoring reads it, through the stage's own column list and mapper. */
    private ScoreCandidate scoreCandidate(long id) {
        return jdbc.queryForObject(
                "SELECT " + ScoreCandidate.COLUMNS + " FROM offer WHERE id = ?", ScoreCandidate::of, id);
    }

    private String system() throws IOException {
        return message("system");
    }

    private String user() throws IOException {
        return message("user");
    }

    /** One message's text out of the one request the model received, whatever shape it is in. */
    private String message(String role) throws IOException {
        List<ServeEvent> events = MODEL.getAllServeEvents();
        assertThat(events).hasSize(1);
        JsonNode body = JSON.readTree(events.getFirst().getRequest().getBodyAsString());
        for (JsonNode message : body.path("messages")) {
            if (!role.equals(message.path("role").asText())) {
                continue;
            }
            JsonNode content = message.path("content");
            if (content.isTextual()) {
                return content.asText();
            }
            StringBuilder text = new StringBuilder();
            content.forEach(part -> text.append(part.path("text").asText("")));
            return text.toString();
        }
        throw new AssertionError("no " + role + " message in " + body);
    }

    /** Every row a call could conceivably touch, as text, to compare before and after. */
    private String rows() {
        return jdbc.queryForObject("""
                SELECT coalesce((SELECT string_agg(row_to_json(o)::text, '|' ORDER BY o.id) FROM offer o), '')
                    || '#' || coalesce((SELECT string_agg(row_to_json(r)::text, '|' ORDER BY r.id)
                                        FROM offer_score_reason r), '')
                    || '#' || coalesce((SELECT string_agg(row_to_json(l)::text, '|' ORDER BY l.portal, l.digest)
                                        FROM content_block_label l), '')
                """, String.class);
    }

    /** The {@code version:} of the rules in force, as {@code ScoreWriter} writes it. */
    private String currentRuleset() {
        return String.valueOf(config.snapshot().rules().version());
    }

    /** An offer each of the three stages has answered, as they write it. */
    private long answered() {
        long id = jdbc.queryForObject(
                """
            INSERT INTO offer (source_id, external_id, title, description, url, fingerprint, status, full_text)
            VALUES (?, ?, 'Senior Java Entwickler (m/w/d)', 'Kurzbeschreibung.', 'https://example.invalid/x',
                    'fp', 'PASSED', ?)
            RETURNING id
            """,
                Long.class,
                sourceId,
                "ext-" + System.nanoTime(),
                "Jetzt bewerben\n\n" + ADVERT + "\n\n" + PRIVACY);
        try {
            jdbc.update(
                    """
                    UPDATE offer SET
                        content_blocks = ?::jsonb,
                        start_text = 'ab sofort', starts_on = DATE '2026-10-01',
                        duration = '12 Monate', duration_months = 12,
                        fields_model = 'test-model', score_model = 'test-model', ruleset_version = ?
                    WHERE id = ?
                    """,
                    JSON.writeValueAsString(List.of(
                            Map.of("index", 2, "text", PRIVACY, "kind", "LEGAL", "reason", "privacy", "by", "MODEL"),
                            Map.of(
                                    "index",
                                    0,
                                    "text",
                                    "Jetzt bewerben",
                                    "kind",
                                    "CHROME",
                                    "reason",
                                    "button",
                                    "by",
                                    "RULE"),
                            Map.of("index", 1, "text", ADVERT, "kind", "CONTENT", "by", "MODEL"))),
                    currentRuleset(),
                    id);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        reason(id, "core_skill_overlap", 45, null, 0);
        reason(id, "role_fit", 15, null, 1);
        reason(id, "vague_description", -10, null, 2);
        reason(id, "interest_fit", 5, "kotlin", 3);
        return id;
    }

    private void reason(long offerId, String factor, int points, String topic, int position) {
        jdbc.update(
                "INSERT INTO offer_score_reason (offer_id, factor, label, points, position, topic)"
                        + " VALUES (?, ?, 'a label', ?, ?, ?)",
                offerId,
                factor,
                points,
                position,
                topic);
    }

    /** A whole chat completion carrying {@code content}, after {@link #DELAY_MILLIS}. */
    private static void replies(String content) {
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
                            Map.of("role", "assistant", "content", content),
                            "finish_reason",
                            "stop"))));
            MODEL.stubFor(post(urlPathEqualTo("/chat/completions"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withFixedDelay(DELAY_MILLIS)
                            .withHeader("Content-Type", "application/json")
                            .withBody(body)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The shipped defaults with two scoring candidates and a content model at WireMock, and a
     * small allowance. Every
     * other placeholder resolves from {@code ConfigFixtures.NEUTRAL_PLACEHOLDERS}.
     */
    private static Path modelAtWireMock() {
        try {
            Path dir = Files.createTempDirectory("leadgen-answer-service");
            dir.toFile().deleteOnExit();
            ConfigFixtures.materialize(dir);
            Path pipeline = dir.resolve("pipeline.yaml");
            String text = Files.readString(pipeline, StandardCharsets.UTF_8);
            text = set(text, "provider", "openai-compatible");
            text = set(text, "base_url", MODEL.baseUrl());
            text = set(text, "api_key", "test-key");
            text = set(text, "scoring", "test-model");
            text = set(text, "scoring_options", "other-model");
            // A model routed to one stage only: the incumbent of a question once routing is
            // adopted, and on no judge list.
            text = set(text, "content", "content-model");
            text = set(text, "max_calls_per_day", String.valueOf(ALLOWANCE));
            Files.writeString(pipeline, text, StandardCharsets.UTF_8);
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** One scalar of the shipped {@code pipeline.yaml}, addressed by key whatever its padding. */
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
