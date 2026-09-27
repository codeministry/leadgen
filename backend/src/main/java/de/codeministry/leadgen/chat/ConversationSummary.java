/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.chat;

import java.time.Instant;

/**
 * One line of the conversation list.
 *
 * @param id        the conversation; the value of {@code ?chat=}.
 * @param title     the first question, shortened; empty until the first question is asked, which
 *                  the drawer shows as a new conversation.
 * @param updatedAt when the last turn started, which is what the list sorts on, newest first.
 */
public record ConversationSummary(long id, String title, Instant updatedAt) {}
