/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.chat;

import static org.assertj.core.api.Assertions.assertThat;

import de.codeministry.leadgen.Databases;
import de.codeministry.leadgen.chat.suggest.FollowUp;
import de.codeministry.leadgen.chat.suggest.FollowUps;
import de.codeministry.leadgen.chat.suggest.Suggestion;
import de.codeministry.leadgen.chat.suggest.SuggestionScope;
import de.codeministry.leadgen.chat.suggest.SuggestionService;
import de.codeministry.leadgen.config.ConfigRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
 * ISC-456 and ISC-457: a chat model phrases the candidates and the follow-ups in the reader's
 * language, paid from the chat's day and never from {@code llm.budget}, once per data snapshot;
 * without a model, with the chat budget at zero, or with a model that fails, the catalog's own
 * sentences are shown. Follow-ups come from a finished turn's stored tool calls only, and every id
 * they name is in that turn's ledger.
 */
@SpringBootTest
@Testcontainers
class SuggestionPhrasingTest {

    private static final ModelStub MODEL = ModelStub.start();

    /** Created once, never inside the property supplier — see {@code LlmBudgetTest}. */
    private static final Path STUBBED = MODEL.configuration().resolve("pipeline.yaml");

    private static final String ORIGINAL = read(STUBBED);

    private static final Pattern ID = Pattern.compile("#(\\d+)");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = Databases.postgres();

    @DynamicPropertySource
    static void configuration(DynamicPropertyRegistry registry) {
        registry.add("leadgen.config-dir", () -> STUBBED.getParent().toString());
    }

    @Autowired
    private SuggestionService suggestions;

    @Autowired
    private FollowUps followUps;

    @Autowired
    private ConversationRepository conversations;

    @Autowired
    private ConfigRegistry config;

    @Autowired
    private JdbcTemplate jdbc;

    private long sourceId;

    @AfterAll
    static void stop() {
        MODEL.close();
    }

    @BeforeEach
    void reset() throws IOException {
        MODEL.reset();
        // No RESTART IDENTITY: every test starts at a run id no earlier test saw, so a phrasing
        // cached by one test is never the next one's snapshot.
        jdbc.execute("TRUNCATE application_event, application, pipeline_run, offer, source CASCADE");
        jdbc.update("DELETE FROM chat_conversation");
        jdbc.update("DELETE FROM chat_call_budget");
        jdbc.update("DELETE FROM llm_call_budget");
        sourceId =
                jdbc.queryForObject("INSERT INTO source (name, kind) VALUES ('test', 'file') RETURNING id", Long.class);
        run(0);
        configure(ORIGINAL);
    }

    @Test
    void twoOpensWithoutADataChangeSpendOneCallAndAChangeSpendsASecond() {
        MODEL.enqueue(ModelStub.text(Duration.ZERO, phrased("0")));
        MODEL.enqueue(ModelStub.text(Duration.ZERO, phrased("0")));

        List<Suggestion> first = suggestions.suggestions(SuggestionScope.NONE, "de");
        List<Suggestion> second = suggestions.suggestions(SuggestionScope.NONE, "de");

        assertThat(MODEL.bodies()).hasSize(1);
        assertThat(MODEL.bodies().getFirst()).contains("German");
        assertThat(first)
                .extracting(Suggestion::text)
                .containsExactly(
                        "Welches der 0 Angebote auf der Shortlist passt?", "Wie stehen die 0 offenen Bewerbungen?");
        assertThat(second).isEqualTo(first);

        run(0);
        suggestions.suggestions(SuggestionScope.NONE, "de");

        assertThat(MODEL.bodies()).hasSize(2);
        assertThat(chatCalls()).isEqualTo(2);
        assertThat(llmCalls()).isZero();
    }

    @Test
    void withoutAModelTheCatalogSentencesAreShown() throws IOException {
        configure(ORIGINAL.replaceAll("(?m)^(\\s*)(chat|scoring): \\S.*$", "$1$2: \"\""));

        List<Suggestion> shown = suggestions.suggestions(SuggestionScope.NONE, "en");

        assertThat(shown)
                .extracting(Suggestion::trigger)
                .containsExactly(SuggestionService.SHORTLIST, SuggestionService.OPEN_APPLICATIONS);
        assertThat(shown)
                .extracting(Suggestion::text)
                .containsExactly(
                        "0 offers are on the shortlist. Which fits my profile best?",
                        "0 applications are open. Where does each one stand?");
        assertThat(MODEL.bodies()).isEmpty();
        assertThat(chatCalls()).isZero();
        assertThat(llmCalls()).isZero();
    }

    @Test
    void aChatBudgetOfZeroShowsTheCatalogAndSendsNothing() throws IOException {
        configure(ORIGINAL.replace("${CHAT_MAX_CALLS_PER_DAY:200}", "0"));
        MODEL.enqueue(ModelStub.text(Duration.ZERO, phrased("0")));

        List<Suggestion> shown = suggestions.suggestions(SuggestionScope.NONE, "de");

        assertThat(shown)
                .extracting(Suggestion::text)
                .containsExactly(
                        "0 Angebote stehen auf der Shortlist. Welches passt am besten zu meinem Profil?",
                        "0 Bewerbungen sind offen. Wo steht jede davon?");
        assertThat(MODEL.bodies()).isEmpty();
        assertThat(llmCalls()).isZero();
    }

    @Test
    void aFailingModelShowsTheCatalog() {
        // Nothing enqueued: the stub answers 500.
        List<Suggestion> shown = suggestions.suggestions(SuggestionScope.NONE, "en");

        assertThat(MODEL.bodies()).hasSize(1);
        assertThat(shown)
                .extracting(Suggestion::text)
                .containsExactly(
                        "0 offers are on the shortlist. Which fits my profile best?",
                        "0 applications are open. Where does each one stand?");
        assertThat(chatCalls()).isEqualTo(1);
        assertThat(llmCalls()).isZero();
    }

    @Test
    void aPhrasingThatChangesANumberKeepsTheCatalogSentence() {
        MODEL.enqueue(ModelStub.text(Duration.ZERO, phrased("7")));

        List<Suggestion> shown = suggestions.suggestions(SuggestionScope.NONE, "en");

        assertThat(shown)
                .extracting(Suggestion::text)
                .containsExactly(
                        "0 offers are on the shortlist. Which fits my profile best?",
                        "0 applications are open. Where does each one stand?");
    }

    @Test
    void theEmptyChatShowsAtMostFourInTheCandidatesOrder() throws IOException {
        configure(ORIGINAL.replaceAll("(?m)^(\\s*)(chat|scoring): \\S.*$", "$1$2: \"\""));
        run(3);
        LocalDate today = LocalDate.now();
        offer("closing", today.plusDays(2));
        long quiet = offer("quiet", null);
        jdbc.update(
                "INSERT INTO application (offer_id, status, sent_on) VALUES (?, 'SENT', ?)",
                quiet,
                Date.valueOf(today.minusDays(30)));

        List<Suggestion> shown = suggestions.suggestions(SuggestionScope.NONE, "en");

        assertThat(shown).hasSize(4);
        assertThat(shown)
                .extracting(Suggestion::trigger)
                .containsExactly(
                        SuggestionService.NEW_OFFERS,
                        SuggestionService.DEADLINES,
                        SuggestionService.NO_REPLY,
                        SuggestionService.SHORTLIST);
    }

    @Test
    void aFinishedTurnGetsTwoOrThreeFollowUpsNamingOnlyItsLedgersIds() throws IOException {
        configure(ORIGINAL.replaceAll("(?m)^(\\s*)(chat|scoring): \\S.*$", "$1$2: \"\""));
        long conversation = conversations.create(null);
        long done = turn(conversation, "DONE");
        toolCall(done, 1, "search_offers", "[{\"kind\":\"OFFER\",\"id\":41},{\"kind\":\"OFFER\",\"id\":42}]");
        toolCall(done, 2, "statistics", "[]");
        long stopped = turn(conversation, "STOPPED");
        toolCall(stopped, 1, "search_offers", "[{\"kind\":\"OFFER\",\"id\":43}]");
        long incomplete = turn(conversation, "INCOMPLETE");

        List<FollowUp> shown = followUps.of(conversation, done, "en").orElseThrow();

        assertThat(shown).hasSizeBetween(2, 3);
        assertThat(shown)
                .extracting(FollowUp::text)
                .allSatisfy(text -> assertThat(ids(text)).isSubsetOf(Set.of(41L, 42L)));
        assertThat(shown.stream().map(FollowUp::text).flatMap(text -> ids(text).stream()))
                .isNotEmpty();
        assertThat(followUps.of(conversation, stopped, "en")).contains(List.of());
        assertThat(followUps.of(conversation, incomplete, "en")).contains(List.of());
        assertThat(followUps.of(conversation + 1000, done, "en")).isEmpty();
    }

    @Test
    void followUpsArePhrasedOnceUnderTheChatBudget() {
        long conversation = conversations.create(null);
        long done = turn(conversation, "DONE");
        toolCall(done, 1, "search_offers", "[{\"kind\":\"OFFER\",\"id\":41},{\"kind\":\"OFFER\",\"id\":42}]");
        MODEL.enqueue(ModelStub.text(
                Duration.ZERO, "{\"offers\":\"Wie unterscheiden sich #41 und #42?\",\"fit\":\"Passt #41 zu mir?\"}"));

        List<FollowUp> first = followUps.of(conversation, done, "de").orElseThrow();
        List<FollowUp> second = followUps.of(conversation, done, "de").orElseThrow();

        assertThat(MODEL.bodies()).hasSize(1);
        assertThat(first)
                .extracting(FollowUp::text)
                .contains("Wie unterscheiden sich #41 und #42?", "Passt #41 zu mir?");
        assertThat(second).isEqualTo(first);
        assertThat(chatCalls()).isEqualTo(1);
        assertThat(llmCalls()).isZero();
    }

    /** A model reply phrasing the two evergreen candidates, both naming {@code number}. */
    private static String phrased(String number) {
        return "```json\n{\"shortlist\":\"Welches der " + number + " Angebote auf der Shortlist passt?\","
                + "\"openApplications\":\"Wie stehen die " + number + " offenen Bewerbungen?\"}\n```";
    }

    private void run(int written) {
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO pipeline_run (
                    started_at, finished_at, ruleset_version, score_model, status,
                    documents, extracted, written, merged,
                    filter_considered, filter_passed,
                    enrich_considered, enriched, incomplete, from_cache, requests,
                    score_considered, scored, unscored, shortlisted, review, submitted,
                    packaged, digest_written)
                VALUES (?, ?, '1', 'm', 'COMPLETE', 1, ?, ?, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, true)
                """, Timestamp.from(now.minusSeconds(60)), Timestamp.from(now), written, written);
    }

    private long offer(String key, LocalDate applyBy) {
        return jdbc.queryForObject(
                """
                INSERT INTO offer (source_id, external_id, title, url, fingerprint, status, score_band,
                                   score_value, apply_by, tags, ingested_at)
                VALUES (?, ?, ?, ?, ?, 'PASSED', 'SHORTLISTED', 95, ?, '{}'::text[], now())
                RETURNING id
                """,
                Long.class,
                sourceId,
                key,
                "Offer " + key,
                "https://example.invalid/" + key,
                key,
                applyBy == null ? null : Date.valueOf(applyBy));
    }

    private long turn(long conversation, String state) {
        long id = conversations.startTurn(conversation, "question " + state, ModelStub.MODEL);
        jdbc.update("UPDATE chat_turn SET state = ?, finished_at = now() WHERE id = ?", state, id);
        return id;
    }

    private void toolCall(long turn, int ordinal, String tool, String returnedIds) {
        jdbc.update(
                "INSERT INTO chat_tool_call (turn_id, ordinal, tool, label, returned_ids) VALUES (?, ?, ?, ?, ?::jsonb)",
                turn,
                ordinal,
                tool,
                tool,
                returnedIds);
    }

    private static Set<Long> ids(String text) {
        Matcher matcher = ID.matcher(text);
        java.util.Set<Long> ids = new java.util.HashSet<>();
        while (matcher.find()) {
            ids.add(Long.parseLong(matcher.group(1)));
        }
        return ids;
    }

    private int chatCalls() {
        return jdbc.queryForObject("SELECT coalesce(sum(calls), 0) FROM chat_call_budget", Integer.class);
    }

    private int llmCalls() {
        return jdbc.queryForObject("SELECT coalesce(sum(calls), 0) FROM llm_call_budget", Integer.class);
    }

    private void configure(String pipeline) throws IOException {
        Files.writeString(STUBBED, pipeline, StandardCharsets.UTF_8);
        config.reload();
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
