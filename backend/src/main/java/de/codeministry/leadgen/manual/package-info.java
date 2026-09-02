/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */

/**
 * Offers found by hand, and the review that stands in front of them.
 *
 * <p>An offer nobody's newsletter carried must still be able to enter the pipeline, or the
 * shortlist quietly stops being the whole picture. This is a {@code file} source and not a new
 * mechanism: an upload only has to put the document where that source is already looking, so
 * copying one in by hand works with no interface at all.
 *
 * <p><b>An upload becomes an offer only when somebody confirms it.</b> A pasted advert can be
 * read wrongly and a frontmatter key spelled differently is ignored in silence; without the
 * step in between, a bad reading enters the shortlist, which is the one list that gets trusted
 * instead of the mailbox. There is no staging table: <b>the file is the state</b>. It can be
 * read with {@code cat}, confirming is a move, and a rejected upload is a file that was
 * deleted rather than a row nobody looks at again.
 *
 * <p>{@code ManualDocumentName} is the whole attack surface and it is one file. What a name
 * may contain and where the result may land are decided separately, and both run on every
 * path: a rule enforced only by construction stops being enforced the first time construction
 * changes.
 */
package de.codeministry.leadgen.manual;
