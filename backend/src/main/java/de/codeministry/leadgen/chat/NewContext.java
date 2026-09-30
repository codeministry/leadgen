/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.chat;

import java.util.List;

/**
 * The body of {@code PUT /api/v1/chat/conversations/{id}/context}: the whole list, which replaces
 * whatever the conversation held.
 *
 * @param context the chips in the order they are shown; empty unpins everything.
 */
public record NewContext(List<ChatContextItem> context) {}
