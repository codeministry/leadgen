/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.answer;

/**
 * One candidate model's answer to one bounded question about one advert, beside the answer
 * the current configuration already stored for it, in the same shape.
 *
 * <p>{@code answer} and {@code stored} are the same {@link QuestionAnswer} type, so the script
 * compares objects rather than prose. {@code raw} keeps the unparsed reply beside the parsed
 * one, because a smaller model's malformed answer is exactly what a measurement has to see.
 *
 * <ul>
 *   <li>{@code blocks} — {@link BlocksAnswer}: the kinds of the blocks whose stored {@code by}
 *       is {@code MODEL}, from {@code offer.content_blocks};</li>
 *   <li>{@code fields} — {@link FieldsAnswer}: {@code start_text}/{@code starts_on},
 *       {@code duration}/{@code duration_months}, {@code apply_by_text}/{@code apply_by};</li>
 *   <li>{@code judge} — {@link JudgeAnswer}: the judged {@code offer_score_reason} rows, factor
 *       and points, an absent factor reading 0.</li>
 * </ul>
 *
 * <p>A {@code judge} comparison is refused when the stored judgement was made under another
 * ruleset {@code version:}, and it assumes the profile has not changed since the advert was
 * judged: a re-total against a changed profile overwrites {@code profile_digest} without
 * asking the judge again, so the stored points may answer an earlier profile.
 *
 * @param question the question that was asked, by its wire name ({@link AnswerQuestion#key()}).
 * @param model    the candidate that answered, as named in the request and allowlisted.
 * @param answer   the candidate's reply, parsed by the stage's own reader. The question's empty
 *                 shape when the reply could not be read — every block {@code CONTENT}, every
 *                 field null, every factor 0 — with {@code raw} holding the reply, so it counts
 *                 as a disagreement. Null only when no answer arrived at all: the call failed in
 *                 transport (unreachable, refused, timed out), which has spent its budget unit,
 *                 or the configuration could not build a client for the model, which has not.
 * @param stored   the stored answer to the same question. Never null on a 200: an offer with
 *                 nothing stored is refused before any model is asked.
 * @param raw      the candidate's reply text, unparsed, or null.
 * @param millis   how long the model call took, in milliseconds.
 */
public record ModelAnswer(
        String question, String model, QuestionAnswer answer, QuestionAnswer stored, String raw, long millis) {}
