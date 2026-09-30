/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.chat;

import java.time.LocalDate;

/**
 * One chip of a conversation's context, as a request body carries it. Each kind reads its own
 * fields and leaves the others null.
 *
 * @param kind    what it pins.
 * @param offerId the offer; {@link ChatContextKind#OFFER} only.
 * @param query   the shortlist's query string, empty for the unfiltered list; {@link
 *                ChatContextKind#SHORTLIST_VIEW} only.
 * @param from    the window's first day; {@link ChatContextKind#ANALYTICS_WINDOW} only.
 * @param to      the window's last day, inclusive; {@link ChatContextKind#ANALYTICS_WINDOW} only.
 */
public record ChatContextItem(ChatContextKind kind, Long offerId, String query, LocalDate from, LocalDate to) {}
