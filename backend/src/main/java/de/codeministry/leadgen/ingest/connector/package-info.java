/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */

/**
 * Where documents come from. One implementation per transport, chosen by a source's
 * {@code type}.
 *
 * <p>Two rules that both exist because their failure is invisible.
 *
 * <p><b>Progress is an IMAP keyword of our own, one name per instance.</b> The receiver writes the
 * connection's {@code progress_flag} on every message its search returns and asks for
 * {@code NOT KEYWORD <flag>} next time; there is no cursor and {@code commit} is empty. Two
 * instances on one mailbox need two names, or they split its mail between them without a word.
 * A new name re-reads the folder once, which the upsert on {@code (source_id, external_id)}
 * makes harmless. The UIDVALIDITY cursor this package used to describe was dropped in V21.
 *
 * <p><b>Progress is never tracked by seen/unseen.</b> The owner reads the same mailbox on a
 * phone, so a seen-based cursor would skip whatever was opened there first. Reading must also
 * not mark anything read, which takes both a read-only folder <em>and</em>
 * {@code mail.imap.peek}.
 */
package de.codeministry.leadgen.ingest.connector;
