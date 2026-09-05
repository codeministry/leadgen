/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.enrich;

import de.codeministry.leadgen.config.ConfigRegistry;
import de.codeministry.leadgen.config.model.PipelineConfig;
import java.util.List;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Fetches the original ad for everything that cleared the hard filter.
 *
 * <p>This is the first stage that leaves the machine, and the only one that can fail for
 * reasons that have nothing to do with the offer. So it never discards: a fetch that is
 * forbidden, rate-limited, unreachable or unreadable leaves the offer in the pipeline with
 * a note saying why. Scoring then judges an incomplete offer as incomplete, which is a
 * decision someone can review — unlike an offer that quietly stopped existing.
 *
 * <p>It runs after the filter and not before, because fetching a thousand ads to then
 * discard eight hundred of them would be rude to the portals and slow for nothing.
 */
@Slf4j
@Service
public class EnrichmentService {

    private static final String DUE =
            """
            SELECT id, url FROM offer
            WHERE status = 'PASSED' AND archived_at IS NULL
              AND enriched_at IS NULL AND url IS NOT NULL
            ORDER BY id
            """;

    private static final String RECORD =
            """
            UPDATE offer
            SET rate_eur = ?, duration = ?, workload = ?, remote_percent = ?, starts_on = ?,
                contact = ?, full_text = ?, enriched_at = now(), enrichment_note = ?
            WHERE id = ?
            """;

    private final ConfigRegistry config;
    private final PageCache cache;
    private final JdbcClient jdbc;

    EnrichmentService(ConfigRegistry config, PageCache cache, DataSource dataSource) {
        this.config = config;
        this.cache = cache;
        this.jdbc = JdbcClient.create(dataSource);
    }

    @Transactional
    public EnrichmentReport run() {
        PipelineConfig.Enrichment settings = config.snapshot().application().enrichment();
        if (settings == null || !settings.enabled()) {
            log.info("Enrichment is disabled; offers stay as the source stated them");
            return EnrichmentReport.skipped();
        }
        if (!"patterns".equals(settings.extract().strategy())) {
            log.warn(
                    "enrichment.extract.strategy is '{}', which is not implemented; nothing is enriched",
                    settings.extract().strategy());
            return EnrichmentReport.skipped();
        }

        List<Due> due = jdbc.sql(DUE)
                .query((rs, row) -> new Due(rs.getLong("id"), rs.getString("url")))
                .list();

        AdFetcher fetcher = new AdFetcher(settings.fetch(), cache);
        AdExtractor extractor = new AdExtractor(settings.extract());
        int enriched = 0;
        int incomplete = 0;
        int fromCache = 0;
        int requests = 0;
        int deferred = 0;

        for (Due offer : due) {
            FetchResult fetched = fetcher.fetch(offer.url());
            // Nothing is written, so the offer is still due next time. The due query is
            // `enriched_at IS NULL`, and recording this would answer it forever.
            if (fetched.deferred()) {
                deferred++;
                continue;
            }
            if (fetched.fromCache()) {
                fromCache++;
            } else if (fetched.status() > 0) {
                requests++;
            }

            Enrichment result;
            if (!fetched.succeeded()) {
                result = Enrichment.incomplete(fetched.note());
            } else {
                Enrichment extracted = extractor.extract(fetched.body(), offer.url());
                result = extracted.fieldCount() == 0
                        ? Enrichment.incomplete("the ad was read but stated none of the fields")
                        : extracted;
            }

            if (result.complete()) {
                enriched++;
            } else {
                incomplete++;
            }
            record(offer.id(), result);
        }

        var report = new EnrichmentReport(due.size(), enriched, incomplete, fromCache, requests, deferred);
        log.info(
                "Enrichment: {} due, {} enriched, {} incomplete, {} from cache, {} requests,"
                        + " {} deferred by the rate limit and due again",
                report.considered(),
                report.enriched(),
                report.incomplete(),
                report.fromCache(),
                report.requests(),
                report.deferred());
        return report;
    }

    private void record(long id, Enrichment enrichment) {
        jdbc.sql(RECORD)
                .params(
                        enrichment.rateEur(),
                        enrichment.duration(),
                        enrichment.workload(),
                        enrichment.remotePercent(),
                        enrichment.startsOn(),
                        enrichment.contact(),
                        enrichment.fullText(),
                        enrichment.note(),
                        id)
                .update();
    }

    private record Due(long id, String url) {}
}
