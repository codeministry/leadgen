/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */

/**
 * Writing offers, and remembering how far a source has been read.
 *
 * <p>The upsert on {@code (source_id, external_id)} is what makes re-reading free, and
 * re-reading is the normal case: a newsletter repeats what is still open. It is also why
 * "written" counts rows touched, insert or update alike.
 *
 * <p>That collapse is <b>not</b> deduplication. It collapses one <em>listing</em> seen twice;
 * one <em>project</em> advertised by several portals is {@code dedupe}'s job, and confusing
 * the two makes a second run look as though it had doubled the archive.
 */
package de.codeministry.leadgen.ingest.store;
