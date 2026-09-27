/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.chat;

/**
 * The body of {@code POST /api/v1/chat/conversations}.
 *
 * @param pinnedOfferId the offer to pin, or null for a conversation about everything.
 */
public record NewConversation(Long pinnedOfferId) {}
