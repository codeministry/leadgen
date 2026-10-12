/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.chat.catalog;

import java.util.List;

/**
 * {@code GET /api/v1/chat/models}: what the chat's model select offers, read from each configured
 * connection rather than from a list in {@code .env} (spec 025, ISC-505). A turn may name only a
 * model this answer listed (ISC-506).
 *
 * @param connections the primary connection first, then the second one when it is configured.
 */
public record ChatModelsView(List<ConnectionView> connections) {}
