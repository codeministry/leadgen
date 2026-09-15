/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */

/**
 * When an engagement starts, how long it runs, and by when it has to be answered.
 *
 * <p>The three facts a person actually sorts adverts by, and the three the regexes in
 * {@code enrichment.extract.fields} are worst at. {@code start_date} there is one pattern for
 * one German date format, so "ab sofort", "Q4/2026" and "Start: KW 42" all yield nothing;
 * {@code duration} captures a bare number, so the column holds "6" rather than what the
 * advert said; and a deadline has no pattern at all because the newsletter never states one —
 * it is in the fetched advert, phrased a different way by every agency. A regex table that
 * has to cover that is a regex table nobody can keep in step, and the failure is silent: an
 * unmatched pattern is indistinguishable from an advert that said nothing.
 *
 * <h2>Each fact is a pair</h2>
 *
 * <p>The phrase the advert used, and a normalised value. {@code start_text} beside {@code
 * starts_on}, {@code duration} beside {@code duration_months}, {@code apply_by_text} beside
 * {@code apply_by}. The phrase is what a person reads and is often the whole truth — "ab
 * sofort", "zunächst 6 Monate mit Option auf Verlängerung" — while the normalised value is
 * what a sort key and a filter can compare. Keeping only one of the two loses either the
 * reading or the ordering, and this stage exists to provide both.
 *
 * <h2>Where it sits, and why</h2>
 *
 * <p>After {@code CONTENT} and before {@code SCORE}. After content because it reads the
 * advert through {@link de.codeministry.leadgen.content.ContentText}, so a deadline invented
 * out of a cookie banner or a portal footer is the same class of error as a tag cloud counted
 * as skill overlap. Before scoring because what it writes is an input to {@code RuleScorer}'s
 * {@code project_setup} factor and to the judge's description of an offer — which is also why
 * it nulls {@code score_model} when it changed something, the self-healing mechanism the
 * content stage already uses rather than a fourth staleness criterion.
 *
 * <h2>Rules before model, still</h2>
 *
 * <p>With no model configured the stage is skipped and logged, and the columns keep whatever
 * the enrichment regexes wrote. There is no deterministic half to run here — unlike content,
 * where a pattern can label a block for free — so "weaker without a language model" means the
 * old regex values rather than nothing at all.
 *
 * <p>What is asked of the model is bounded in the same way the judge's answer is: three
 * facts, a closed JSON shape, an ISO date or nothing, and a month count that is discarded if
 * it is outside a range any real engagement falls in. The model is never allowed to write
 * prose that becomes the advert.
 */
package de.codeministry.leadgen.fields;
