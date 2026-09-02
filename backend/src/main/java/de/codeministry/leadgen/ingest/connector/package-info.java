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
 * <p><b>The cursor advances after the write, never after the read.</b> {@code commit} exists
 * for exactly that: a cursor moved at read time plus a failure afterwards means those
 * documents are never looked at again, and nothing says so. It advances only over messages
 * actually processed, because a mail the selector skipped is not progress.
 *
 * <p><b>Progress is never tracked by seen/unseen.</b> The owner reads the same mailbox on a
 * phone, so a flag-based cursor would skip whatever was opened there first. It is
 * UIDVALIDITY plus UID, and a changed UIDVALIDITY voids every UID the server ever handed out.
 * Reading must also not mark anything read, which takes both a read-only folder <em>and</em>
 * {@code mail.imap.peek}.
 */
package de.codeministry.leadgen.ingest.connector;
