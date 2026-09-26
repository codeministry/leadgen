/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.enrich;

import de.codeministry.leadgen.concurrent.BoundedWork;
import de.codeministry.leadgen.config.ConfigRegistry;
import de.codeministry.leadgen.config.model.PipelineConfig;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

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
@RequiredArgsConstructor
public class EnrichmentService {

    /**
     * The note for a page that answered but whose {@code full_text} rules matched nothing on it.
     * The other notes name a failed fetch; this one names a fetch that worked and an extraction
     * that did not, which is a question for the portal's rules and not for the portal.
     */
    public static final String NO_AD_TEXT = "the page was read, but no ad text could be extracted from it";

    private static final String DUE = """
        SELECT id, url FROM offer
        WHERE status = 'PASSED' AND archived_at IS NULL
          AND enriched_at IS NULL AND url IS NOT NULL
        ORDER BY id
        """;

    /**
     * {@link #DUE} for one id, and without {@code enriched_at IS NULL}.
     *
     * <p>That one condition is what the button exists to lift: a failed fetch stamps
     * {@code enriched_at} too, so an offer whose ad was refused once is never due again. The
     * rest stays, because an offer no later stage would touch — filtered out, archived, or
     * without a URL to ask — is not one to fetch for. Restoring it is what puts it back.
     *
     * <p>And an offer that already has its ad is not one either. A fetch that fails records
     * its reason over the enrichment columns, the text among them, so a press on an offer
     * whose page answers 500 today would throw away an ad that was read fine last week.
     */
    private static final String FETCHABLE = """
        SELECT id, url FROM offer
        WHERE status = 'PASSED' AND archived_at IS NULL
          AND url IS NOT NULL AND full_text IS NULL AND id = ?
        """;

    /**
     * A fetch that failed or read nothing for a field answers null there, and null never
     * overwrites a value the newsletter or FIELDS already stored: a refused refetch used to
     * wipe the duration a card had shown a minute earlier.
     */
    private static final String RECORD = """
        UPDATE offer
        SET rate_eur = COALESCE(?, rate_eur), duration = COALESCE(?, duration),
            workload = COALESCE(?, workload), remote_percent = COALESCE(?, remote_percent),
            starts_on = COALESCE(?, starts_on), contact = COALESCE(?, contact),
            full_text = ?, enriched_at = now(), enrichment_note = ?
        WHERE id = ?
        """;

    /**
     * The button's write: the same statement, and a no-op once the offer has its text. The
     * lookup refuses an offer that has one, but two presses can both pass it, and the one whose
     * page failed would then record its reason over the ad the other one just stored.
     */
    private static final String RECORD_UNLESS_READ =
            RECORD.replace("WHERE id = ?", "WHERE id = ? AND full_text IS NULL");

    private final ConfigRegistry config;
    private final PageCache cache;
    private final JdbcClient jdbc;
    private final FetchWindow window;

    /**
     * <b>Deliberately not {@code @Transactional}, and that is what lets a pass wait.</b>
     * Each offer's result is one statement and nothing here needs atomicity across offers —
     * a partially enriched batch is a correct batch with fewer ads in it, and the due query
     * finds the rest. Held as one transaction it was already the shape {@code ScoringService}
     * documents: a write lock on every offer touched, kept for the length of the stage. With
     * the fetcher now waiting for rate-limit permits, that length is minutes by design, and
     * any concurrent run's filter stage — which writes a verdict on every row — would sit
     * behind it.
     */
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

        // One fetcher for the whole pass, shared by every worker: the budget of `max_per_run` is
        // the pass's, so it has to be one counter however many fetches are in flight.
        AdFetcher fetcher = new AdFetcher(settings.fetch(), cache, window);
        AdExtractor extractor = new AdExtractor(settings.extract());

        // Above width 1 the body runs on a virtual thread of its own. Nothing in it relies on
        // the caller's thread: no transaction (see above), and each write is one statement.
        int width = due.isEmpty() ? 1 : settings.fetch().concurrency();
        List<Outcome> outcomes = BoundedWork.forEach(width, due, offer -> {
            FetchResult fetched = fetcher.fetch(offer.url());
            // Nothing is written, so the offer is still due next time. The due query is
            // `enriched_at IS NULL`, and recording this would answer it forever. Reached
            // only once the run's whole fetch budget is spent, not at the first refusal, and
            // the loop goes on: every later offer is deferred the same way, and costs nothing.
            if (fetched.deferred()) {
                return new Outcome(fetched, false);
            }
            return new Outcome(
                    fetched,
                    settle(offer, fetched, extractor, RECORD).enrichment().complete());
        });

        int enriched = 0;
        int incomplete = 0;
        int fromCache = 0;
        int requests = 0;
        int deferred = 0;
        for (Outcome outcome : outcomes) {
            FetchResult fetched = outcome.fetched();
            if (fetched.deferred()) {
                deferred++;
                continue;
            }
            if (fetched.fromCache()) {
                fromCache++;
            } else if (fetched.status() > 0) {
                requests++;
            }
            if (outcome.complete()) {
                enriched++;
            } else {
                incomplete++;
            }
        }

        var report = new EnrichmentReport(due.size(), enriched, incomplete, fromCache, requests, deferred, width);
        log.info(
                "Enrichment: {} due, {} enriched, {} incomplete, {} from cache, {} requests,"
                        + " {} beyond this run's fetch budget and due again{}",
                report.considered(),
                report.enriched(),
                report.incomplete(),
                report.fromCache(),
                report.requests(),
                report.deferred(),
                BoundedWork.atWidth(report.width()));
        return report;
    }

    /**
     * The nightly stage for one offer, now, and past a failure the cache remembers.
     *
     * <p>What it shares with {@link #run} is everything after the fetch, so the button and the
     * night cannot disagree about what an answer means. What it does not share is the fetch
     * itself: {@link AdFetcher#fetchFresh} skips the cache, which is the point, and asks the
     * shared window once instead of waiting, because a person is watching. Not
     * {@code @Transactional} for the reason {@link #run} documents: the fetch is a network call,
     * and the one write after it is one statement.
     *
     * @return what was recorded, complete or not, and whether this call was the one that
     * recorded it. A failed fetch is an outcome and is written down with its reason; only a
     * refused permit writes nothing. The write is a no-op once the offer has its text (the race
     * the statement exists for), and {@link Settled#stored()} is false then.
     * @throws NotFetchable when enrichment is off, or the offer is not one the night would fetch.
     * @throws NoPermit     when the shared fetch window has no permit this minute.
     */
    public Settled runFor(long id) {
        PipelineConfig.Enrichment settings = config.snapshot().application().enrichment();
        if (settings == null || !settings.enabled()) {
            throw new NotFetchable("enrichment is disabled, so no ad is fetched");
        }
        if (!"patterns".equals(settings.extract().strategy())) {
            throw new NotFetchable("enrichment.extract.strategy is '%s', which is not implemented"
                    .formatted(settings.extract().strategy()));
        }

        Due offer = jdbc.sql(FETCHABLE)
                .param(id)
                .query((rs, row) -> new Due(rs.getLong("id"), rs.getString("url")))
                .optional()
                .orElseThrow(() -> new NotFetchable(("offer %d is not one to fetch: its ad is already here, or it has"
                                + " not cleared the hard filter, is archived, or has no URL")
                        .formatted(id)));

        FetchResult fetched = new AdFetcher(settings.fetch(), cache, window).fetchFresh(offer.url());
        // A refused permit is a fact about the minute, as at night; recorded, it would replace
        // the offer's note with a sentence about the rate limit and say nothing about the page.
        if (fetched.deferred()) {
            throw new NoPermit(fetched.note());
        }
        Settled settled = settle(offer, fetched, new AdExtractor(settings.extract()), RECORD_UNLESS_READ);
        Enrichment result = settled.enrichment();
        log.info(
                "Offer {} fetched again on request: {}",
                id,
                !settled.stored()
                        ? "its text landed meanwhile, nothing recorded"
                        : result.complete() ? "enriched" : result.note());
        return settled;
    }

    /**
     * What an answer from the portal means for one offer, and writing it down. Shared by the
     * run and the button, so the two cannot disagree about it.
     */
    private Settled settle(Due offer, FetchResult fetched, AdExtractor extractor, String statement) {
        Enrichment result;
        if (!fetched.succeeded()) {
            result = Enrichment.incomplete(fetched.note());
        } else {
            Enrichment extracted = extractor.extract(fetched.body(), offer.url());
            // No ad text is the case the card and the toast have to explain, whatever else the
            // patterns found on the page: a rate without the advert is still an offer without its
            // ad. What was found is kept, and the note says what is missing.
            result = extracted.fullText() == null || extracted.fullText().isBlank()
                    ? extracted.withNote(NO_AD_TEXT)
                    : extracted;
        }
        return new Settled(result, record(statement, offer.id(), result) == 1);
    }

    /**
     * The shared fetch window has no permit this minute, and nothing was written. A reason
     * rather than a stack trace, because it reaches a button.
     */
    public static class NoPermit extends RuntimeException {
        NoPermit(String message) {
            super(message);
        }
    }

    /**
     * Not an offer the night would fetch, or enrichment is switched off. A state of the server
     * rather than a bad request, the same kind of answer as {@code ScoringService.NoJudge}.
     */
    public static class NotFetchable extends RuntimeException {
        NotFetchable(String message) {
            super(message);
        }
    }

    /** @return how many rows the statement wrote: one, or zero when its condition held it back. */
    private int record(String statement, long id, Enrichment enrichment) {
        return jdbc.sql(statement)
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

    /**
     * What one offer of a pass came to, returned by its worker so the report is folded over the
     * list afterwards rather than counted from several threads.
     *
     * @param complete whether what was recorded is complete; false for a deferral, which records
     *     nothing
     */
    private record Outcome(FetchResult fetched, boolean complete) {}

    /**
     * What one fetch settled on, and whether this call was the one that wrote it down.
     *
     * <p>The night's statement always writes its row. The button's is held back once the offer
     * has its text, so two presses that both passed the lookup store it once: the one whose
     * write took is the one that derives and scores, and the other derives nothing, because
     * nothing in the row came from what it fetched.
     *
     * @param enrichment what the fetch yielded, complete or with its note
     * @param stored whether this call's write reached the row
     */
    public record Settled(Enrichment enrichment, boolean stored) {}
}
