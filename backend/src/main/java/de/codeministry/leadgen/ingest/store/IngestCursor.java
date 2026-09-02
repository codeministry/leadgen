/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.ingest.store;

/**
 * How far a mailbox folder has been read.
 *
 * @param uidValidity the folder's identity. When the server changes it, every UID it ever
 *     handed out is void — the folder was recreated, and a cursor kept across that would
 *     silently skip its whole contents.
 * @param lastUid the highest UID processed. Zero means nothing yet.
 */
public record IngestCursor(long uidValidity, long lastUid) {

    public static final IngestCursor NONE = new IngestCursor(0, 0);

    public IngestCursor validFor(long currentUidValidity) {
        return uidValidity == currentUidValidity ? this : new IngestCursor(currentUidValidity, 0);
    }
}
