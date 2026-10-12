/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.chat;

import de.codeministry.leadgen.config.ConfigRegistry;
import de.codeministry.leadgen.config.model.PipelineConfig;
import de.codeministry.leadgen.llm.Connections;
import java.time.LocalDate;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The chat's own ceilings: how many requests a day it may send, and how many tool rounds one turn
 * may take before it has to answer.
 *
 * <p><b>Beside {@code LlmBudget}, never through it.</b> A long afternoon of questions must not
 * starve the nightly run, and a spent night must not silence the chat. The count therefore lives
 * in its own table, {@code chat_call_budget}, because {@code llm_call_budget} is keyed by the day
 * alone and has no room for a second counter; the semantics are the pipeline's, copied rather than
 * shared: one statement checks and increments, and zero is no calls.
 *
 * <p><b>Absent is the shipped default, never no ceiling.</b> Unlike {@code llm.budget}, where an
 * absent block means no ceiling, an absent {@code chat.max_calls_per_day} is
 * {@link #DEFAULT_CALLS_PER_DAY} and an absent {@code chat.max_tool_rounds} is
 * {@link #DEFAULT_TOOL_ROUNDS}. Configuration overrides whole files, so an installation whose own
 * {@code pipeline.yaml} predates the {@code chat:} block reads no block at all — and the chat is on
 * by default wherever a scoring model exists. Without a round bound, a model that keeps asking for
 * tools looped for ever on a pool thread; without a daily ceiling, nothing metered it.
 *
 * <p><b>The second connection has a ceiling of its own, beside the chat's</b> (spec 025, ISC-510).
 * A call on {@code chat.connection} takes one from {@code chat.max_calls_per_day} and one from
 * {@code chat.connection.max_calls_per_day}, in one transaction: when either refuses, the other is
 * rolled back, so a refused call counts on neither. A call on the primary takes only the chat's. The
 * second ceiling reads {@link #DEFAULT_SECOND_CALLS_PER_DAY} where it is absent, never no ceiling.
 *
 * <p><b>What "no" means is the turn's.</b> A refused call or a spent round count ends the turn with
 * its reason ({@link ChatErrorReason#BUDGET}, {@link ChatErrorReason#ROUNDS}) rather than with an
 * answer the model never finished; this class only answers the question.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatBudget {

    /** {@code chat.max_calls_per_day} when the key or the whole {@code chat:} block is absent. */
    public static final int DEFAULT_CALLS_PER_DAY = 200;

    /** {@code chat.max_tool_rounds} when the key or the whole {@code chat:} block is absent. */
    public static final int DEFAULT_TOOL_ROUNDS = 6;

    /**
     * {@code chat.connection.max_calls_per_day} when the key is absent; the shipped
     * {@code pipeline.yaml} names the same default for its placeholder.
     */
    public static final int DEFAULT_SECOND_CALLS_PER_DAY = 50;

    /**
     * Check and increment in one statement, as in {@code LlmBudget}: between a {@code SELECT} and
     * an {@code UPDATE} two turns could both take the last call.
     */
    private static final String TAKE = """
            INSERT INTO chat_call_budget (day, calls) VALUES (current_date, 1)
            ON CONFLICT (day) DO UPDATE SET calls = chat_call_budget.calls + 1
             WHERE chat_call_budget.calls < :limit
            RETURNING calls
            """;

    /** The same one-statement take, per connection, for the second connection's own ceiling. */
    private static final String TAKE_CONNECTION = """
            INSERT INTO chat_connection_call_budget (day, connection, calls) VALUES (current_date, :connection, 1)
            ON CONFLICT (day, connection) DO UPDATE SET calls = chat_connection_call_budget.calls + 1
             WHERE chat_connection_call_budget.calls < :limit
            RETURNING calls
            """;

    /** The day a spent budget was logged, so it is one line and not one per refused call. */
    private final AtomicReference<LocalDate> announced = new AtomicReference<>();

    /** The same for the second connection's ceiling, so neither spent day hides the other's line. */
    private final AtomicReference<LocalDate> announcedSecond = new AtomicReference<>();

    private final ConfigRegistry config;
    private final JdbcClient jdbc;
    private final PlatformTransactionManager transactionManager;

    /**
     * Takes one call from today's chat allowance. Asked before every model call of a turn — the
     * first and each one after a tool round.
     *
     * @return true when the call may be sent; false ends the turn with {@link ChatErrorReason#BUDGET}.
     */
    public boolean take() {
        return take(Connections.Name.PRIMARY);
    }

    /**
     * Takes one call on {@code connection}: on the primary from the chat's allowance alone, on the
     * second from the chat's and the connection's own, both or neither (ISC-510).
     *
     * @return true when the call may be sent; false ends the turn with {@link ChatErrorReason#BUDGET}.
     */
    public boolean take(Connections.Name connection) {
        if (connection != Connections.Name.SECOND) {
            return takeChat();
        }
        int secondLimit = secondLimitOrDefault();
        if (secondLimit <= 0) {
            announceOnce(
                    announcedSecond,
                    "chat.connection.max_calls_per_day is {}, so the chat asks nothing of it today",
                    secondLimit);
            return false;
        }
        Boolean taken = new TransactionTemplate(transactionManager).execute(status -> {
            if (!takeChat()) {
                return false;
            }
            boolean second = jdbc.sql(TAKE_CONNECTION)
                    .param("connection", connection.name())
                    .param("limit", secondLimit)
                    .query(Integer.class)
                    .optional()
                    .isPresent();
            if (!second) {
                // The chat's call taken above is given back: a refused call counts on neither.
                status.setRollbackOnly();
                announceOnce(
                        announcedSecond, "chat.connection.max_calls_per_day of {} is spent for today", secondLimit);
            }
            return second;
        });
        return Boolean.TRUE.equals(taken);
    }

    /** Takes one call from the chat's own counter, as every call on any connection does. */
    private boolean takeChat() {
        int limit = limit();
        if (limit <= 0) {
            announceOnce(announced, "chat.max_calls_per_day is {}, so the chat asks nothing of a model today", limit);
            return false;
        }
        boolean taken = jdbc.sql(TAKE)
                .param("limit", limit)
                .query(Integer.class)
                .optional()
                .isPresent();
        if (!taken) {
            announceOnce(announced, "chat.max_calls_per_day of {} is spent for today", limit);
        }
        return taken;
    }

    /** Today's chat count; reads only. */
    public int used() {
        return jdbc.sql("SELECT coalesce((SELECT calls FROM chat_call_budget WHERE day = current_date), 0)")
                .query(Integer.class)
                .single();
    }

    /** Today's count on {@code connection}: the chat's own for the primary, the connection's own for the second. */
    public int used(Connections.Name connection) {
        if (connection != Connections.Name.SECOND) {
            return used();
        }
        return jdbc.sql("""
                        SELECT coalesce((SELECT calls FROM chat_connection_call_budget
                                          WHERE day = current_date AND connection = :connection), 0)
                        """)
                .param("connection", connection.name())
                .query(Integer.class)
                .single();
    }

    /**
     * {@code chat.connection.max_calls_per_day} as it resolves now; empty when no second connection
     * is configured, the shipped default where the key is absent.
     */
    public OptionalInt secondLimit() {
        PipelineConfig.ChatConnection connection = chat().connection();
        return connection == null || !connection.configured()
                ? OptionalInt.empty()
                : OptionalInt.of(secondLimitOrDefault());
    }

    private int secondLimitOrDefault() {
        PipelineConfig.ChatConnection connection = chat().connection();
        Integer configured = connection == null ? null : connection.maxCallsPerDay();
        return configured == null ? DEFAULT_SECOND_CALLS_PER_DAY : configured;
    }

    /** {@code chat.max_calls_per_day} as it resolves now, the shipped default where it is absent. */
    public int limit() {
        Integer configured = chat().maxCallsPerDay();
        return configured == null ? DEFAULT_CALLS_PER_DAY : configured;
    }

    /** {@code chat.max_tool_rounds} as it resolves now, the shipped default where it is absent. */
    public int toolRounds() {
        Integer configured = chat().maxToolRounds();
        return configured == null ? DEFAULT_TOOL_ROUNDS : configured;
    }

    /**
     * A fresh round counter for one turn, bound to the ceiling configured now: a reload in the
     * middle of a turn does not move the goalposts of the turn already running.
     */
    public Rounds rounds() {
        return new Rounds(toolRounds());
    }

    /** Absent block and absent keys alike are the shipped defaults, never a zero nobody wrote. */
    private PipelineConfig.Chat chat() {
        PipelineConfig.Chat chat = config.snapshot().application().chat();
        return chat == null ? new PipelineConfig.Chat(null, null) : chat;
    }

    private static void announceOnce(AtomicReference<LocalDate> day, String message, Object argument) {
        LocalDate today = LocalDate.now();
        if (!today.equals(day.getAndSet(today))) {
            log.warn(message, argument);
        }
    }

    /**
     * The tool rounds one turn has taken. Not a bean and not shared: each turn asks
     * {@link #rounds()} once and keeps the answer.
     */
    public static final class Rounds {

        private final int limit;
        private final AtomicInteger taken = new AtomicInteger();

        Rounds(int limit) {
            this.limit = limit;
        }

        /**
         * Takes the next tool round.
         *
         * @return false once {@code chat.max_tool_rounds} are spent; the turn then ends with
         * {@link ChatErrorReason#ROUNDS}. A refused round is not counted.
         */
        public boolean next() {
            return taken.getAndUpdate(n -> n < limit ? n + 1 : n) < limit;
        }
    }
}
