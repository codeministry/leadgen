/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.enrich;

import de.codeministry.leadgen.content.ContentService;
import de.codeministry.leadgen.fields.FieldsService;
import de.codeministry.leadgen.score.ScoringService;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Fetches one offer's original ad again, because somebody pressed the button, and brings what
 * was derived from the old text up to date.
 *
 * <p>The nightly stages narrowed to one id, in the night's order — enrichment, segmentation,
 * field extraction, scoring — rather than a second path through them: every stage already
 * encodes what it records and when, and a button that took its own route would start
 * disagreeing with the night the first time one of them changed.
 *
 * <p><b>Deliberately not {@code @Transactional}.</b> The fetch goes over the network and the
 * later stages may ask a model, and a transaction held across either would keep write locks
 * for as long as a portal or a model takes to answer — the same reason
 * {@link EnrichmentService#run()} is not one. Each stage commits its own write.
 */
@Slf4j
@Service
public class OfferRefetch {

    /**
     * Makes the two derived stages due for this offer again. Only after a fetch that stored
     * text: before it, a failed fetch would leave an offer whose blocks and fields were
     * thrown away for nothing.
     */
    private static final String REDERIVE = """
        UPDATE offer SET content_at = NULL, fields_at = NULL WHERE id = ?
        """;

    private final EnrichmentService enrichment;
    private final ContentService content;
    private final FieldsService fields;
    private final ScoringService scoring;
    private final JdbcClient jdbc;

    OfferRefetch(
            EnrichmentService enrichment,
            ContentService content,
            FieldsService fields,
            ScoringService scoring,
            DataSource dataSource) {
        this.enrichment = enrichment;
        this.content = content;
        this.fields = fields;
        this.scoring = scoring;
        this.jdbc = JdbcClient.create(dataSource);
    }

    /**
     * A fetch that reached the page and failed is recorded by enrichment and ends here: the
     * offer keeps its blocks, fields and score, because nothing they were derived from changed.
     *
     * @throws EnrichmentService.NotFetchable when the night would not fetch this offer either
     * @throws EnrichmentService.NoPermit when the shared window has no request left this minute;
     *     nothing is written then
     */
    public void refetch(long id) {
        Enrichment fetched = enrichment.runFor(id);
        if (fetched.fullText() == null || fetched.fullText().isBlank()) {
            return;
        }
        jdbc.sql(REDERIVE).param(id).update();
        content.runFor(id);
        fields.runFor(id);
        try {
            scoring.scoreFor(id);
        } catch (ScoringService.NoJudge e) {
            // No scoring section at all, so there are no weights to score against. The ad is
            // stored and derived; answering the request as failed would say the fetch failed.
            log.warn("Offer {} fetched again but not scored: {}", id, e.getMessage());
        }
    }
}
