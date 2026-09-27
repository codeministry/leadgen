/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */

/**
 * Asking the corpus in a chat: offers, the operator's applications and profile, through
 * read-only tools on the services the screens already use.
 *
 * <p>Not a pipeline stage. Nothing here runs at night, and no stage reads what this package
 * writes; without a chat model the package answers that it is absent and the tool is the tool
 * it was.
 *
 * <p><b>Every offer or application an answer names points at a row a tool returned in the same
 * turn.</b> The model marks what it cites, the turn keeps a ledger of what its tools returned,
 * and a citation the ledger does not hold reaches the screen as marked text rather than as a
 * link. The same idea as {@code ask}, at corpus scale: an answer over forty offers cannot quote
 * forty adverts, but it can be held to the ids it was shown.
 *
 * <p>The wire contract is {@link de.codeministry.leadgen.chat.ChatEvent} and its six records,
 * sent as server-sent events, and the conversation records beside them.
 */
package de.codeministry.leadgen.chat;
