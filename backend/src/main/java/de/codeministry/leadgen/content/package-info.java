/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */

/**
 * Which parts of a fetched advert are the advert.
 *
 * <p>{@code full_text} is what a portal's page contained, and a portal's page contains a
 * great deal that is not the offer: a meta row, an apply button, a report dialog with its
 * four radio labels, and a tag cloud of sixty technology names taken from the site's own
 * taxonomy rather than from the client's requirements. Measured on the live corpus, the
 * advert of one offer did not begin until two thirds of the way down the stored text.
 *
 * <p><b>This is a fix to the score before it is a fix to the screen.</b> {@code RuleScorer}
 * folds the advert into one haystack for skill matching and the judge is handed the same
 * text, so a portal tag cloud inflates the overlap that decides the shortlist. The screen
 * being three times too long is the visible half of the same defect.
 *
 * <h2>The model decides; determinism is a cache in front of it</h2>
 *
 * <p>The obvious arrangement — selectors that strip known furniture, a model for the rest —
 * is the wrong way round here, and the corpus says why. One portal is 88 % of the offers, so
 * a selector table is a maintenance bet on one site's markup, and a selector that stops
 * matching fails <em>silently</em>: fewer blocks removed looks exactly like a cleaner advert.
 * Worse, the part that costs the most is unreachable by any selector at all — the recruiter's
 * own signature, the postal address, the company register, the privacy link, sits inside the
 * description container and differs per agency.
 *
 * <p>So every block is normalised and hashed, and {@link
 * de.codeministry.leadgen.content.BlockLabelStore} remembers what a digest was decided to be.
 * A known digest is free. An unknown one costs one model call and is free from then on. The
 * furniture a portal repeats in every advert is paid for once; a header form nobody has seen
 * is a digest nobody has seen, which is one call and then free again. <b>Novelty is noticed
 * by construction rather than mislabelled in silence.</b>
 *
 * <h2>Fail open</h2>
 *
 * <p>A block that no rule matched, that the cache does not know and that no model answered
 * about stays {@link de.codeministry.leadgen.content.ContentKind#CONTENT} and stays on the
 * screen. Nothing is hidden without a positive decision to hide it, and {@code
 * content_undecided} counts what nobody decided — the number that makes a changed markup
 * visible instead of silent.
 *
 * <h2>What is stored, and where</h2>
 *
 * <p>{@code offer.full_text} is never edited: it is the record of what was fetched. The
 * blocks live beside it in {@code offer.content_blocks}, carrying their text inline, which
 * costs a second copy and buys three things — the browser needs no splitter of its own, the
 * indices cannot slip, and a later change of mind in the shared cache cannot rewrite what was
 * decided for an offer that has already been read.
 */
package de.codeministry.leadgen.content;
