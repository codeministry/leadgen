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
import de.codeministry.leadgen.config.ConfigFixtures;
import de.codeministry.leadgen.config.ConfigRegistry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * ISC-521: {@code GET /api/v1/chat/status} names the chat's spend per connection and in total, today
 * and this calendar month, summing stored costs only, and counts the turns each window left out for
 * an unknown price.
 *
 * <p>The seed is priced turns today, yesterday, earlier this month and last month on both
 * connections, two billed turns without a price today, and one turn from before V40 that counts
 * nowhere. Each turn is ended through {@link ConversationRepository#finish}, the one write that adds
 * a turn to the {@code chat_spend} ledger the status reads. Every date is relative to
 * {@link LocalDate#now()}, the day the call ceilings count: on
 * the 1st of a month there is no "earlier this month" and yesterday belongs to last month, so the
 * expected month sums are built from the same dates the rows carry rather than written down.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ChatSpendTest {

    private static final ModelStub PRIMARY = ModelStub.start();
    private static final ModelStub SECOND = ModelStub.start();

    /** Created once, never inside the property supplier — see {@code LlmBudgetTest}. */
    private static final Path CONFIG_DIRECTORY = configDirectory();

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = Databases.postgres();

    @DynamicPropertySource
    static void configuration(DynamicPropertyRegistry registry) {
        registry.add("leadgen.config-dir", CONFIG_DIRECTORY::toString);
    }

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ConfigRegistry config;

    @Autowired
    private ConversationRepository conversations;

    private long conversation;
    private int ordinal;

    @AfterAll
    static void stop() {
        PRIMARY.close();
        SECOND.close();
    }

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM chat_conversation");
        jdbc.update("DELETE FROM chat_spend");
        write(true);
        conversation =
                jdbc.queryForObject("INSERT INTO chat_conversation (title) VALUES ('spend') RETURNING id", Long.class);
        ordinal = 0;
    }

    @Test
    void theStatusSumsTodaysAndThisMonthsCostsPerConnectionAndInTotal() {
        LocalDate today = LocalDate.now();
        LocalDate yesterday = today.minusDays(1);
        LocalDate lastMonth = today.withDayOfMonth(1).minusDays(1);
        boolean earlierExists = today.getDayOfMonth() > 1;

        BigDecimal primaryToday = new BigDecimal("0.01234567");
        BigDecimal primaryMonth = primaryToday;
        turn("PRIMARY", today, primaryToday);
        // A turn from before V37 names no connection and counts as the primary.
        if (earlierExists) {
            turn(null, today.withDayOfMonth(1), new BigDecimal("0.25"));
            primaryMonth = primaryMonth.add(new BigDecimal("0.25"));
        }
        turn("PRIMARY", yesterday, new BigDecimal("0.1"));
        if (inMonth(yesterday, today)) {
            primaryMonth = primaryMonth.add(new BigDecimal("0.1"));
        }
        turn("PRIMARY", lastMonth, new BigDecimal("5"));

        BigDecimal secondToday = new BigDecimal("1.50000001");
        turn("SECOND", today, new BigDecimal("1.5"));
        turn("SECOND", today, new BigDecimal("0.00000001"));
        BigDecimal secondMonth = secondToday;
        if (earlierExists) {
            turn("SECOND", today.withDayOfMonth(1), new BigDecimal("0.04"));
            secondMonth = secondMonth.add(new BigDecimal("0.04"));
        }
        turn("SECOND", yesterday, new BigDecimal("0.3"));
        if (inMonth(yesterday, today)) {
            secondMonth = secondMonth.add(new BigDecimal("0.3"));
        }
        turn("SECOND", lastMonth, new BigDecimal("7"));
        // Billed without a price: tokens stored, cost unknown, left out and counted.
        turn("SECOND", today, null);
        turn("SECOND", today, null);
        // Before V40: no tokens, no cost — counted nowhere.
        jdbc.update(
                "INSERT INTO chat_turn (conversation_id, ordinal, question, state, connection, created_at)"
                        + " VALUES (?, ?, 'q', 'DONE', 'SECOND', ?)",
                conversation,
                ++ordinal,
                noon(today));

        var status =
                assertThat(mvc.get().uri("/api/v1/chat/status")).hasStatusOk().bodyJson();
        status.extractingPath("$.spend.connections[*].name").isEqualTo(List.of("PRIMARY", "SECOND"));
        status.extractingPath("$.spend.connections[0].today").isEqualTo(wire(primaryToday));
        status.extractingPath("$.spend.connections[0].month").isEqualTo(wire(primaryMonth));
        status.extractingPath("$.spend.connections[0].unpricedToday").isEqualTo(0);
        status.extractingPath("$.spend.connections[0].unpricedMonth").isEqualTo(0);
        status.extractingPath("$.spend.connections[1].today").isEqualTo(wire(secondToday));
        status.extractingPath("$.spend.connections[1].month").isEqualTo(wire(secondMonth));
        status.extractingPath("$.spend.connections[1].unpricedToday").isEqualTo(2);
        status.extractingPath("$.spend.connections[1].unpricedMonth").isEqualTo(2);
        status.extractingPath("$.spend.today").isEqualTo(wire(primaryToday.add(secondToday)));
        status.extractingPath("$.spend.month").isEqualTo(wire(primaryMonth.add(secondMonth)));
    }

    /**
     * The ledger, not the turns: deleting a conversation — one, or several at once — cascades to its
     * turns and must not un-spend what they cost. Before V41 the status summed {@code chat_turn},
     * so a deleted conversation lowered the month's spend.
     */
    @Test
    void deletingAConversationKeepsTheMonthsSpend() {
        LocalDate today = LocalDate.now();
        turn("PRIMARY", today, new BigDecimal("0.5"));
        turn("SECOND", today, new BigDecimal("1.25"));
        turn("SECOND", today, null);
        long other =
                jdbc.queryForObject("INSERT INTO chat_conversation (title) VALUES ('other') RETURNING id", Long.class);
        long kept = conversation;
        conversation = other;
        turn("SECOND", today, new BigDecimal("2"));
        conversation = kept;

        assertThat(mvc.delete().uri("/api/v1/chat/conversations/" + kept)).hasStatus2xxSuccessful();
        assertThat(mvc.post()
                        .uri("/api/v1/chat/conversations/bulk-delete")
                        .contentType("application/json")
                        .content("{\"ids\":[" + other + "]}"))
                .hasStatusOk();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_turn", Integer.class))
                .as("both conversations' turns are gone")
                .isZero();

        var status =
                assertThat(mvc.get().uri("/api/v1/chat/status")).hasStatusOk().bodyJson();
        status.extractingPath("$.spend.connections[0].month").isEqualTo("0.5");
        status.extractingPath("$.spend.connections[1].month").isEqualTo("3.25");
        status.extractingPath("$.spend.connections[1].unpricedMonth").isEqualTo(1);
        status.extractingPath("$.spend.today").isEqualTo("3.75");
        status.extractingPath("$.spend.month").isEqualTo("3.75");
    }

    /**
     * A turn still streaming when its conversation is deleted is gone before {@code finish} runs,
     * so the delete books what its stored rounds already cost — priced, unpriced and unreported
     * alike — in the same transaction, single delete and bulk delete both.
     */
    @Test
    void deletingAConversationMidTurnBooksTheTurnsStoredRounds() {
        LocalDate today = LocalDate.now();
        streaming("PRIMARY", today, new BigDecimal("0.75"), false);
        streaming("SECOND", today, null, false);
        long other =
                jdbc.queryForObject("INSERT INTO chat_conversation (title) VALUES ('other') RETURNING id", Long.class);
        long kept = conversation;
        conversation = other;
        streaming("SECOND", today, new BigDecimal("2"), false);
        streaming("SECOND", today, null, true);
        conversation = kept;

        assertThat(mvc.delete().uri("/api/v1/chat/conversations/" + kept)).hasStatus2xxSuccessful();
        assertThat(mvc.post()
                        .uri("/api/v1/chat/conversations/bulk-delete")
                        .contentType("application/json")
                        .content("{\"ids\":[" + other + "]}"))
                .hasStatusOk();

        var status =
                assertThat(mvc.get().uri("/api/v1/chat/status")).hasStatusOk().bodyJson();
        status.extractingPath("$.spend.connections[0].month").isEqualTo("0.75");
        status.extractingPath("$.spend.connections[1].month").isEqualTo("2");
        status.extractingPath("$.spend.connections[1].unpricedMonth").isEqualTo(1);
        status.extractingPath("$.spend.connections[1].unreportedMonth").isEqualTo(1);
        status.extractingPath("$.spend.month").isEqualTo("2.75");
    }

    /**
     * The two reasons a turn stores no cost are counted apart: a billed model nobody priced is
     * unpriced, a turn a delivered model round of which reported no usage is unreported — whether
     * its model has a price or not, and whether it kept the tokens of the rounds that did report or
     * none at all. Neither adds to a sum.
     */
    @Test
    void aTurnWithUnreportedUsageIsCountedApartFromAnUnpricedOne() {
        LocalDate today = LocalDate.now();
        turn("SECOND", today, new BigDecimal("0.5"));
        turn("SECOND", today, null);
        unreported("SECOND", today, 1000, 100);
        unreported("SECOND", today, 0, 0);
        unreported("PRIMARY", today, 0, 0);

        var status =
                assertThat(mvc.get().uri("/api/v1/chat/status")).hasStatusOk().bodyJson();
        status.extractingPath("$.spend.connections[0].unpricedToday").isEqualTo(0);
        status.extractingPath("$.spend.connections[0].unreportedToday").isEqualTo(1);
        status.extractingPath("$.spend.connections[0].unreportedMonth").isEqualTo(1);
        status.extractingPath("$.spend.connections[1].today").isEqualTo("0.5");
        status.extractingPath("$.spend.connections[1].unpricedToday").isEqualTo(1);
        status.extractingPath("$.spend.connections[1].unpricedMonth").isEqualTo(1);
        status.extractingPath("$.spend.connections[1].unreportedToday").isEqualTo(2);
        status.extractingPath("$.spend.connections[1].unreportedMonth").isEqualTo(2);
        status.extractingPath("$.spend.today").isEqualTo("0.5");
    }

    @Test
    void withoutASecondConnectionThePrimaryIsListedAloneAtZero() {
        write(false);

        var status =
                assertThat(mvc.get().uri("/api/v1/chat/status")).hasStatusOk().bodyJson();
        status.extractingPath("$.spend.connections[*].name").isEqualTo(List.of("PRIMARY"));
        status.extractingPath("$.spend.connections[0].today").isEqualTo("0");
        status.extractingPath("$.spend.connections[0].month").isEqualTo("0");
        status.extractingPath("$.spend.connections[0].unpricedToday").isEqualTo(0);
        status.extractingPath("$.spend.connections[0].unpricedMonth").isEqualTo(0);
        status.extractingPath("$.spend.connections[0].unreportedToday").isEqualTo(0);
        status.extractingPath("$.spend.connections[0].unreportedMonth").isEqualTo(0);
        status.extractingPath("$.spend.today").isEqualTo("0");
        status.extractingPath("$.spend.month").isEqualTo("0");
    }

    /**
     * A turn started at noon on {@code day} with tokens stored and {@code cost}, null for unpriced,
     * then ended as a running turn is, which adds it to the ledger.
     */
    private void turn(String connection, LocalDate day, BigDecimal cost) {
        long id = jdbc.queryForObject(
                "INSERT INTO chat_turn (conversation_id, ordinal, question, state, connection, created_at,"
                        + " input_tokens, output_tokens, cost_usd) VALUES (?, ?, 'q', 'STREAMING', ?, ?, 100, 20, ?)"
                        + " RETURNING id",
                Long.class,
                conversation,
                ++ordinal,
                connection,
                noon(day),
                cost);
        conversations.finish(id, ChatTurnState.DONE, List.of(), List.of());
    }

    /**
     * A turn on {@code day} whose usage a delivered round never reported: the tokens of the rounds
     * that did, no cost, and the row marked, as the turn writes it; then ended like {@link #turn}.
     */
    private void unreported(String connection, LocalDate day, int input, int output) {
        long id = jdbc.queryForObject(
                "INSERT INTO chat_turn (conversation_id, ordinal, question, state, connection, created_at,"
                        + " input_tokens, output_tokens, unreported) VALUES (?, ?, 'q', 'STREAMING', ?, ?, ?, ?, true)"
                        + " RETURNING id",
                Long.class,
                conversation,
                ++ordinal,
                connection,
                noon(day),
                input,
                output);
        conversations.finish(id, ChatTurnState.DONE, List.of(), List.of());
    }

    /** A turn still streaming on {@code day}, its rounds' tokens and {@code cost} stored, never ended. */
    private void streaming(String connection, LocalDate day, BigDecimal cost, boolean unreported) {
        jdbc.update(
                "INSERT INTO chat_turn (conversation_id, ordinal, question, state, connection, created_at,"
                        + " input_tokens, output_tokens, cost_usd, unreported)"
                        + " VALUES (?, ?, 'q', 'STREAMING', ?, ?, 100, 20, ?, ?)",
                conversation,
                ++ordinal,
                connection,
                noon(day),
                cost,
                unreported);
    }

    private static Timestamp noon(LocalDate day) {
        return Timestamp.valueOf(LocalDateTime.of(day, LocalTime.NOON));
    }

    private static boolean inMonth(LocalDate day, LocalDate today) {
        return day.getYear() == today.getYear() && day.getMonth() == today.getMonth();
    }

    private static String wire(BigDecimal amount) {
        return amount.stripTrailingZeros().toPlainString();
    }

    /** The shipped files, the primary pointed at its stub, the second connection only when asked. */
    private void write(boolean second) {
        writeTo(CONFIG_DIRECTORY, second);
        config.reload();
    }

    private static void writeTo(Path directory, boolean second) {
        ConfigFixtures.materialize(directory);
        Path pipeline = directory.resolve("pipeline.yaml");
        try {
            String yaml = ModelStub.pointLlmAt(Files.readString(pipeline, StandardCharsets.UTF_8), PRIMARY.baseUrl());
            if (second) {
                yaml = ModelStub.pointSecondAt(yaml, "openai-compatible", SECOND.baseUrl());
            }
            Files.writeString(pipeline, yaml, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path configDirectory() {
        try {
            Path dir = Files.createTempDirectory("leadgen-chat-spend");
            dir.toFile().deleteOnExit();
            writeTo(dir, true);
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
