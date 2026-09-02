/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.ingest.store;

import de.codeministry.leadgen.ingest.ExtractedOffer;
import java.sql.Types;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes extracted offers. Plain SQL against the schema Flyway owns.
 *
 * <p>Storing is an upsert on `(source_id, external_id)`, which makes re-reading the same
 * mail free of consequence — and re-reading is normal: a newsletter arrives daily and
 * repeats what is still open. The count of rows stored is therefore lower than the count
 * of offers extracted, and that difference is not deduplication. Deduplication collapses
 * one *project* that several portals advertise; this collapses one *listing* seen twice.
 */
@Component
public class OfferStore {

    private final JdbcClient jdbc;
    private final JdbcTemplate template;

    OfferStore(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
        this.template = new JdbcTemplate(dataSource);
    }

    /**
     * Records what one source did on one run.
     *
     * <p>Not derivable from the offer table: the number of documents and the count a
     * document announces about itself leave no trace there, and comparing the two is the
     * one check nothing else can make.
     */
    @Transactional
    public void recordRun(long sourceId, int documents, int extracted, int written, Integer announced) {
        jdbc.sql(
                        """
                        INSERT INTO source_run (source_id, documents, extracted, written, announced)
                        VALUES (?, ?, ?, ?, ?)
                        """)
                .params(sourceId, documents, extracted, written, announced)
                .update();
    }

    /** Creates the source row on first sight and returns its id. */
    @Transactional
    public long sourceId(String name, String kind) {
        return jdbc.sql(
                        """
                        INSERT INTO source (name, kind) VALUES (?, ?)
                        ON CONFLICT (name) DO UPDATE SET kind = EXCLUDED.kind
                        RETURNING id
                        """)
                .params(name, kind)
                .query(Long.class)
                .single();
    }

    /** Returns how many rows the batch actually inserted or updated. */
    @Transactional
    public int store(long sourceId, List<ExtractedOffer> offers) {
        if (offers.isEmpty()) {
            return 0;
        }
        int[][] affected = template.batchUpdate(
                """
                INSERT INTO offer (source_id, external_id, title, description, url, location,
                                   portal, agency, published_on, fingerprint, tags, received_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (source_id, external_id) WHERE external_id IS NOT NULL
                DO UPDATE SET title = EXCLUDED.title,
                              description = EXCLUDED.description,
                              location = EXCLUDED.location,
                              portal = EXCLUDED.portal,
                              agency = EXCLUDED.agency,
                              published_on = EXCLUDED.published_on,
                              fingerprint = EXCLUDED.fingerprint,
                              tags = EXCLUDED.tags,
                              -- The earlier of the two, and never overwritten by a later
                              -- mail. Nine of the measured listings appear in two mails,
                              -- and the question this column answers is when the offer
                              -- first reached the mailbox. LEAST ignores a null, so a row
                              -- imported before this column existed is backfilled by the
                              -- next re-read rather than staying empty.
                              received_at = LEAST(offer.received_at, EXCLUDED.received_at)
                """,
                offers,
                offers.size(),
                (statement, offer) -> {
                    statement.setLong(1, sourceId);
                    statement.setString(2, offer.externalId());
                    statement.setString(3, offer.title());
                    statement.setString(4, offer.description());
                    statement.setString(5, offer.url());
                    statement.setString(6, offer.location());
                    statement.setString(7, offer.portal());
                    statement.setString(8, offer.agency());
                    if (offer.publishedOn() == null) {
                        statement.setNull(9, Types.DATE);
                    } else {
                        statement.setObject(9, offer.publishedOn());
                    }
                    statement.setString(10, offer.fingerprint());
                    statement.setArray(
                            11,
                            statement
                                    .getConnection()
                                    .createArrayOf("text", offer.tags().toArray()));
                    // Null rather than now(): a source that is not a mail has no arrival
                    // date, and the file's own timestamp would be the run's, dressed up.
                    if (offer.receivedAt() == null) {
                        statement.setNull(12, Types.TIMESTAMP_WITH_TIMEZONE);
                    } else {
                        statement.setTimestamp(12, java.sql.Timestamp.from(offer.receivedAt()));
                    }
                });

        return java.util.Arrays.stream(affected)
                .flatMapToInt(java.util.Arrays::stream)
                .map(count -> count > 0 ? 1 : 0)
                .sum();
    }

    public int count(long sourceId) {
        return jdbc.sql("SELECT count(*) FROM offer WHERE source_id = ?")
                .param(sourceId)
                .query(Integer.class)
                .single();
    }
}
