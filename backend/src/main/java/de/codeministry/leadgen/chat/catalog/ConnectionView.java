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
 * One connection in {@link ChatModelsView}.
 *
 * @param name      {@code PRIMARY} or {@code SECOND}, the name a turn and a stored row carry; never
 *                  the base URL.
 * @param kind      the provider kind the connection speaks.
 * @param billed    whether a call on it is paid for, as {@code Connections} resolves it.
 * @param reachable whether the connection answered the model list; {@code false} lists no models.
 * @param reason    why the connection could not be listed, one of a few fixed sentences that never
 *                  name a host, URL, header or body; {@code null} when it was. A spent daily ceiling
 *                  is not a reason here: it is read from {@code GET /chat/status}
 *                  ({@code secondCalls}, {@code secondLimit}).
 * @param models    the models it reported that can call tools.
 */
public record ConnectionView(
        String name, String kind, boolean billed, boolean reachable, String reason, List<ModelView> models) {}
