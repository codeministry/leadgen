/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.ingest.store;

import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Reads and writes the per-folder cursor. */
@Component
public class IngestCursorStore {

    private final JdbcClient jdbc;

    IngestCursorStore(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    public IngestCursor load(long sourceId, String folder) {
        return jdbc.sql("SELECT uid_validity, last_uid FROM ingest_cursor WHERE source_id = ? AND folder = ?")
                .params(sourceId, folder)
                .query((rs, row) -> new IngestCursor(rs.getLong("uid_validity"), rs.getLong("last_uid")))
                .optional()
                .orElse(IngestCursor.NONE);
    }

    @Transactional
    public void save(long sourceId, String folder, IngestCursor cursor) {
        jdbc.sql(
                        """
                        INSERT INTO ingest_cursor (source_id, folder, uid_validity, last_uid)
                        VALUES (?, ?, ?, ?)
                        ON CONFLICT ON CONSTRAINT uq_ingest_cursor
                        DO UPDATE SET uid_validity = EXCLUDED.uid_validity,
                                      last_uid = EXCLUDED.last_uid,
                                      updated_at = now()
                        """)
                .params(sourceId, folder, cursor.uidValidity(), cursor.lastUid())
                .update();
    }
}
