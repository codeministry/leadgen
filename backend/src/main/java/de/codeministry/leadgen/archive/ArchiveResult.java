/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.archive;

/**
 * What one archive-by-hand wrote.
 *
 * <p>Two counts and not the rows. Both existing write endpoints answer with the whole
 * {@code ShortlistEntry} because the browser replaces its row with what the server stored —
 * and that reason does not survive the plural: the shortlist does not replace an archived
 * row, it drops it, because the offer is no longer part of the side being read. Entries here
 * would be fetched for the sole purpose of being discarded, and a page of them is measured in
 * this application at roughly 1.4 KB each.
 *
 * <p>Distinct from {@link ArchiveReport}, which belongs to the age pass and answers a
 * different question.
 *
 * @param requested how many offers the caller named, after duplicates were collapsed. The
 *                  {@code expect_count_from_subject} shape: the caller states its count, the
 *                  server states what it wrote, and a mismatch is loud instead of silent.
 *                  Nothing is ever discarded because of one.
 * @param archived  how many rows the statement actually touched. Lower than {@code requested}
 *                  when an id named no offer, which costs a number and nothing else.
 * @param unscored  how many of those carried no score. The list's own sentence counts
 *                  {@code matched}, {@code total} and {@code unscored} together, so without
 *                  this the third one keeps standing after the offers behind it are gone —
 *                  invisible at one offer, obvious at twenty.
 */
public record ArchiveResult(int requested, int archived, int unscored) {
}
