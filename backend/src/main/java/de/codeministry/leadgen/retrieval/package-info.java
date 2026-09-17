/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */

/**
 * The retrieval vector: one embedding per offer, built from the whole de-furnitured advert.
 *
 * <h2>Two vector columns, and which question each one answers</h2>
 *
 * <p>There are two, they are not interchangeable, and the difference is the one thing to
 * understand before touching either:
 *
 * <ul>
 *   <li>{@code offer.embedding}, written by {@code dedupe.OfferEmbedder}, holds the title, the
 *       location and the advert's <b>opening</b>. It answers "are these two postings the same
 *       project". Deduplication wants a short, discriminating text, and the merge and flag
 *       bands in {@code matching-rules.yaml} were measured against exactly that text.
 *   <li>{@code offer.retrieval_embedding}, written here, holds the title, the location and the
 *       <b>whole advert</b> as {@code ContentText.of} builds it. It answers "what is this
 *       engagement about".
 * </ul>
 *
 * <p><b>Nothing may compare one against the other, and {@code SimilarOffers} must never be
 * pointed at this column.</b> Two vectors of two different texts are numbers describing two
 * different things, and the cosine between them is a number rather than an error — which is the
 * same failure mode {@code embedding_model} exists to prevent between two models, arriving
 * through a door that guard does not watch.
 *
 * <h2>Why the advert is not simply used for both</h2>
 *
 * <p>Not because it is worse. Measured 2026-09-17 over 252 fetched and segmented adverts, both
 * texts across the same rows, it is the better of the two for that question as well: 347 pairs
 * above 0.8 against the teaser's 488, and all ten pairs in the merge band read as one project
 * posted twice. The reason is ordering. DEDUPE runs at pipeline position 2 and the advert does
 * not exist until ENRICH and CONTENT have run, so a shared column would hold both texts at once,
 * permanently. Moving deduplication behind enrichment would mean fetching duplicates before
 * collapsing them, which spends the fetch budget on adverts that are about to be merged away.
 *
 * <h2>Where the stage sits</h2>
 *
 * <p>Between {@code SCORE} and {@code PACKAGE}, which is not where it reads. Its input is ready
 * after {@code CONTENT}, but {@code LlmBudget} is one allowance shared by every stage, and the
 * first pass after this is switched on walks the whole window. In front of the judge that
 * backfill spends the day and the shortlist goes unjudged; behind it, the same backfill degrades
 * only the search. The full argument is in {@code docs/decisions/retrieval.md}.
 */
package de.codeministry.leadgen.retrieval;
