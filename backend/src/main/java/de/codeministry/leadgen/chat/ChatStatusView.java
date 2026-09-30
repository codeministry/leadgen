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
 * {@code GET /api/v1/chat/status}: the chat's own state, which the empty chat's ring shows on hover
 * (spec 022, ISC-476). Read when the popover opens, never carried on the capability, which the header
 * asks once per page.
 *
 * @param model      the chat model a turn asks now.
 * @param callsUsed  today's model calls, against {@code callsLimit}.
 * @param callsLimit {@code chat.max_calls_per_day} as it resolves; {@code 0} asks nothing of a model.
 * @param toolRounds {@code chat.max_tool_rounds} as it resolves: how often one turn may call tools.
 */
public record ChatStatusView(String model, int callsUsed, int callsLimit, int toolRounds) {}
