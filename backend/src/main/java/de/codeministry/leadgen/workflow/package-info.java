/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */

/**
 * The pipeline as a workflow, with every configuration key filed under the stage that reads it.
 *
 * <p>The rules screen used to show four panels of settings with no order between them. This
 * package answers the question the operator actually has — "what happens to an offer, in which
 * order, and which key moves which step" — by walking the phases of {@code docs/BACKEND-FLOWS.md}
 * §1 and the stages {@code IngestService} times, in the order it times them.
 *
 * <p><b>Read from the files, not from the typed snapshot.</b> The records in {@code config.model}
 * hold only what some stage reads, so a key read by nothing would have nowhere to appear. A key
 * no stage claims is shown in the unread group rather than dropped: an unknown key on screen is
 * what the operator should see.
 *
 * <p>Read-only. Nothing here writes a file, and secrets go through the same masking the sources
 * panel uses.
 */
package de.codeministry.leadgen.workflow;
