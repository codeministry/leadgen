/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */

/**
 * The daily overview, rendered to a file.
 *
 * <p>A file, and the last thing a run does. There is no transport, no recipient and no
 * channel here, and none in the configuration either: the block that used to model one was
 * deleted rather than made vendor-neutral, because the honest fix for a schema describing
 * something the tool must not do is to remove the schema.
 *
 * <p>No schedule of its own either. Whatever schedules the run schedules the digest, and a
 * cron nothing reads is one more setting that lies.
 *
 * <p>An unscored offer gets its own heading rather than being sorted to the bottom of a
 * ranking that does not exist.
 */
package de.codeministry.leadgen.digest;
