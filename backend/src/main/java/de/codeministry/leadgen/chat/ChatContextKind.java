/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.chat;

/** What a context chip pins; the values of {@code chat_context.kind}. */
public enum ChatContextKind {
    /** One offer, by id. */
    OFFER,
    /** The shortlist under a query string, as its URL carries it. */
    SHORTLIST_VIEW,
    /** The analytics screen over a window of days. */
    ANALYTICS_WINDOW
}
