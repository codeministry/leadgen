/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.llm;

import de.codeministry.leadgen.config.ConfigRegistry;
import de.codeministry.leadgen.config.model.PipelineConfig;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * How many requests a day may leave for a language model, and whether this one may.
 *
 * <p><b>Every stage asks before it sends</b>, and what "no" means is the stage's own: the
 * judge leaves the offer unscored and therefore due, the field extractor answers with its
 * empty {@code Optional}, the ingest fallback leaves the document where it is. None of them
 * writes a result it did not get, which is what keeps the next run able to finish the work.
 *
 * <p><b>A request is a request.</b> A judge's prompt and an embedding of thirty-two adverts
 * count the same, because the number in the configuration says "calls" and an exception to
 * that would live only in this class rather than beside the number a person reads.
 *
 * <p><b>{@code max_calls_per_day: 0} means none, and no limit is the absent block.</b> The
 * other reading is the expensive one: somebody writing 0 to mean "off" would get a bill,
 * where this way round they get a scoring run that stops and says so on the first day.
 */
@Slf4j
@Component
public class LlmBudget {

    /**
     * Check and increment in one statement. Between a {@code SELECT} and an {@code UPDATE}
     * there is a window where two stages both read the last remaining call and both take it;
     * the {@code WHERE} on the conflict closes it, and a refused call updates nothing and
     * returns no row.
     *
     * <p>The insert for a day's first call is not guarded by that {@code WHERE} — a fresh day
     * starts at zero and the caller has already refused a limit below one.
     */
    private static final String TAKE =
            """
            INSERT INTO llm_call_budget (day, calls) VALUES (current_date, 1)
            ON CONFLICT (day) DO UPDATE SET calls = llm_call_budget.calls + 1
             WHERE llm_call_budget.calls < :limit
            RETURNING calls
            """;

    /**
     * The day this already said something about, so a spent budget is one line and not one
     * per refused call. A nightly pass would otherwise write a few hundred identical lines.
     */
    private final AtomicReference<LocalDate> announced = new AtomicReference<>();

    private final ConfigRegistry config;
    private final JdbcClient jdbc;

    LlmBudget(ConfigRegistry config, DataSource dataSource) {
        this.config = config;
        this.jdbc = JdbcClient.create(dataSource);
    }

    /**
     * Takes one call from today's allowance.
     *
     * @return true when the caller may send its request. False means the day is spent, and
     * the caller leaves its work undone rather than writing a result it never received.
     */
    public boolean take() {
        Integer limit = limit();
        if (limit == null) {
            return true;
        }
        if (limit <= 0) {
            announceOnce("llm.budget.max_calls_per_day is {}, so nothing is asked of a model today", limit);
            return false;
        }
        boolean taken = jdbc.sql(TAKE)
                .param("limit", limit)
                .query(Integer.class)
                .optional()
                .isPresent();
        if (!taken) {
            announceOnce(
                    "llm.budget.max_calls_per_day of {} is spent for today;"
                            + " what is left stays due and the next run finishes it",
                    limit);
        }
        return taken;
    }

    /**
     * Today's count, for a test and for anything that wants to report rather than spend.
     */
    public int used() {
        return jdbc.sql("SELECT coalesce((SELECT calls FROM llm_call_budget WHERE day = current_date), 0)")
                .query(Integer.class)
                .single();
    }

    /**
     * The configured ceiling, or null when there is none.
     *
     * <p>Null is the absent block and not a zero: a fresh clone has no {@code budget:} at all
     * and must not be limited by a number nobody wrote.
     */
    private Integer limit() {
        PipelineConfig.Llm llm = config.snapshot().application().llm();
        if (llm == null || llm.budget() == null) {
            return null;
        }
        return llm.budget().maxCallsPerDay();
    }

    private void announceOnce(String message, Object argument) {
        LocalDate today = LocalDate.now();
        if (!today.equals(announced.getAndSet(today))) {
            log.warn(message, argument);
        }
    }
}
