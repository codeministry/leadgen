/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.llm;

/**
 * Today's model calls against the day's ceiling, for a screen that reports rather than spends.
 *
 * <p>It answers the question a run that left scoring undone raises: was the model down, or was
 * the day's allowance spent? {@link LlmBudget#take()} only ever says "not now", and a stage that
 * leaves its work due looks the same either way.
 *
 * @param used  requests sent today, by the database's own calendar day — the same day
 *              {@code take()} counts against, so the two cannot disagree across midnight.
 * @param limit {@code llm.budget.max_calls_per_day}, or null when no budget is configured at all.
 *              Null is not zero: zero means "ask nothing today", null means "no ceiling".
 */
public record LlmBudgetView(int used, Integer limit) {}
