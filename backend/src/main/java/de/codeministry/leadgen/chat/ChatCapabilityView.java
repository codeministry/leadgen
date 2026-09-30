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
 * Whether the chat exists on this instance.
 *
 * @param present false when neither {@code llm.models.chat} nor {@code llm.models.scoring} names a
 *                model; the header then draws no button.
 */
public record ChatCapabilityView(boolean present) {}
