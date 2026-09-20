/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.analytics;

import de.codeministry.leadgen.config.ConfigRegistry;
import de.codeministry.leadgen.filter.FilterStage;
import de.codeministry.leadgen.ingest.IngestReport;
import de.codeministry.leadgen.score.Judges;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

/**
 * Writes down what a run did, at the moment it is still true.
 *
 * <p>Everything in the report is destroyed by the next run: the filter re-judges every
 * offer and overwrites its verdict, the scorer overwrites its columns and deletes its
 * reasons. The numbers are already assembled here — this only stops them from being handed
 * to the browser and then thrown away.
 *
 * <p><b>Nothing here may fail a run.</b> A history row is worth less than the run that
 * produced it, so every method catches and logs. The same rule the startup banner follows:
 * a note about the work must not be able to end the work.
 */
@Slf4j
@Service
public class PipelineRunRecorder {

    private static final String INSERT = """
        INSERT INTO pipeline_run (
            started_at, ruleset_version, score_model, status,
            documents, extracted, written, merged,
            filter_considered, filter_passed,
            enrich_considered, enriched, incomplete, from_cache, requests,
            score_considered, scored, unscored, shortlisted, review, submitted,
            packaged, digest_written)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        RETURNING id
        """;

    /**
     * The row a run opens with. Zeros and {@code RUNNING}, because at this moment the run
     * has done nothing and must not appear to claim otherwise — the rule the row used to
     * satisfy by not existing yet.
     *
     * <p>It exists so the <i>next</i> run's {@code started_at} is knowable while this one is
     * still going, which is what bounds {@code source_run} correctly. See
     * {@code V15__pipeline_run_starts_open.sql}.
     */
    private static final String OPEN = """
        INSERT INTO pipeline_run (
            started_at, ruleset_version, score_model, status, stage_total,
            documents, extracted, written, merged,
            filter_considered, filter_passed,
            enrich_considered, enriched, incomplete, from_cache, requests,
            score_considered, scored, unscored, shortlisted, review, submitted,
            packaged, digest_written)
        VALUES (?, ?, ?, 'RUNNING', ?, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, false)
        RETURNING id
        """;

    private static final String CLOSE = """
        UPDATE pipeline_run SET
            finished_at = now(), ruleset_version = ?, score_model = ?, status = ?,
            documents = ?, extracted = ?, written = ?, merged = ?,
            filter_considered = ?, filter_passed = ?,
            enrich_considered = ?, enriched = ?, incomplete = ?, from_cache = ?, requests = ?,
            score_considered = ?, scored = ?, unscored = ?, shortlisted = ?, review = ?, submitted = ?,
            packaged = ?, digest_written = ?
        WHERE id = ?
        """;

    private static final String INSERT_STAGE =
            "INSERT INTO pipeline_run_stage (run_id, stage, removed) VALUES (?, ?, ?)";

    private static final String INSERT_TIMING = """
        INSERT INTO pipeline_stage (run_id, position, stage, started_at, ended_at, status, note)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        """;

    /**
     * A batched run is finished by the collector, not by the request that started it, so
     * the row it left behind is the one to complete. Ordered and limited because batching
     * is on for a run or it is not, and there is one operator: two rows awaiting a batch at
     * once would mean two runs overlapping, which nothing here can produce.
     */
    private static final String COMPLETE_AWAITING = """
        UPDATE pipeline_run
        SET status = 'COMPLETE', finished_at = now(), packaged = ?, digest_written = ?,
            scored = scored + ?,
            -- Recomputed rather than passed in: the two band counts are standing totals
            -- in the report as well, and the collection the batch produced does not
            -- carry them. Read here, after the scores landed, they are the same
            -- quantity measured at the right moment. Primaries only and not archived,
            -- like everywhere: this row is what the dashboard shows for that run, and
            -- a historical row counting the archive disagrees with the live screen.
            shortlisted = (SELECT count(*) FROM offer
                           WHERE duplicate_of_id IS NULL AND archived_at IS NULL
                             AND score_band = 'SHORTLISTED'),
            review      = (SELECT count(*) FROM offer
                           WHERE duplicate_of_id IS NULL AND archived_at IS NULL
                             AND score_band = 'REVIEW')
        WHERE id = (
            SELECT id FROM pipeline_run WHERE status = 'AWAITING_BATCH'
            ORDER BY finished_at DESC LIMIT 1
        )
        """;

    private final JdbcClient jdbc;
    private final ConfigRegistry config;
    private final Judges judges;

    PipelineRunRecorder(DataSource dataSource, ConfigRegistry config, Judges judges) {
        this.jdbc = JdbcClient.create(dataSource);
        this.config = config;
        this.judges = judges;
    }

    /**
     * The model that judged, not the one that was asked for.
     *
     * <p>`scoringModel` is the override a request may carry, and it is null on every
     * ordinary run — which left the column empty on exactly the runs that used the
     * configured default. An empty scale is the one thing this column exists to prevent:
     * "a run without its scale is a number with nothing behind it". Resolved through
     * `Judges` rather than re-derived here, so the default stays defined in one place.
     */
    private String effectiveModel(String requested) {
        if (requested != null && !requested.isBlank()) {
            return requested;
        }
        var choices = judges.choices();
        return choices.isEmpty() ? null : choices.getFirst();
    }

    private static final String ABANDON = """
        UPDATE pipeline_run
        SET status = 'ABANDONED', finished_at = now()
        WHERE finished_at IS NULL
        """;

    /**
     * Closes the rows of runs whose process is gone.
     *
     * <p>A pass that is killed mid-flight — a restart, a crash, a rolled deployment — leaves
     * its row open forever, and from the read side that is indistinguishable from a pass that
     * is still going. Measured on the developer database: a row from 2026-09-06 still said
     * RUNNING nine days later, so {@code /api/v1/ingest/current} reported a run in flight and
     * the button would have refused every click from then on.
     *
     * <p>Startup is the exact moment to say so, and no heuristic is needed for it: this
     * process is the only thing that runs a pass — {@code IngestService} holds an in-process
     * lock and the chart runs one replica — so a row still open when it starts belongs to a
     * process that no longer exists. A stale-after-N-hours rule would have been a guess; this
     * is a fact.
     *
     * <p>{@code ABANDONED} rather than {@code COMPLETE}, and {@link #lastRun()}'s reader
     * excludes it: the row carries zeros, and shown under "last run" it would claim a pass
     * that found nothing.
     */
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void abandonOpenRuns() {
        try {
            int closed = jdbc.sql(ABANDON).update();
            if (closed > 0) {
                log.warn("{} run(s) were still open from a process that is gone; closed as ABANDONED", closed);
            }
        } catch (RuntimeException e) {
            log.error("Open runs could not be closed: {}", e.getMessage(), e);
        }
    }

    /**
     * Opens the row for a run that is starting.
     *
     * <p>Returns empty when the row could not be written, and the run goes on without one:
     * a history row is worth less than the run that produced it. {@link #record} then has
     * nothing to complete and says so once, rather than inventing a second row.
     *
     * @param stageTotal how many stages this run will time. Written now because it is a
     *                   property of the configuration the run started under — one stage per enabled
     *                   source plus the fixed ones — and nothing later can recover it.
     */
    public OptionalLong start(Instant startedAt, String scoreModel, int stageTotal) {
        try {
            Long id = jdbc.sql(OPEN)
                    .params(
                            java.sql.Timestamp.from(startedAt),
                            String.valueOf(config.snapshot().rules().version()),
                        effectiveModel(scoreModel),
                        stageTotal)
                    .query(Long.class)
                    .single();
            return OptionalLong.of(id);
        } catch (RuntimeException e) {
            log.error("The run was not opened in the history: {}", e.getMessage(), e);
            return OptionalLong.empty();
        }
    }

    private static final String MARK = """
        UPDATE pipeline_run
        SET stage = ?, stage_position = ?, stage_started_at = now()
        WHERE id = ? AND finished_at IS NULL
        """;

    /**
     * Says which stage the open row is in, overwriting whatever it said before.
     *
     * <p>{@code finished_at IS NULL} in the predicate rather than a status check, and it is
     * the same guard the read side uses: a late marker from a run that has already been
     * closed must not reopen the question of where it is.
     *
     * <p>Swallows everything, like every other write in this class. Progress nobody can read
     * is a worse outcome than a run that ends because its own progress note failed.
     */
    public void mark(long runId, int position, String stage) {
        try {
            jdbc.sql(MARK).params(stage, position, runId).update();
        } catch (RuntimeException e) {
            log.warn("The run's current stage was not recorded: {}", e.getMessage());
        }
    }

    /**
     * The run that just finished, or that is waiting for its batch.
     */
    public void record(
            OptionalLong runId, IngestReport report, Instant startedAt, String scoreModel, List<StageTiming> stages) {
        try {
            // A run that submitted a batch has not packaged anything yet and has not
            // written a digest. Recorded as COMPLETE it would state a shortlist belonging
            // to the previous run, and look entirely normal doing it.
            boolean awaiting = report.scored().submitted() > 0;
            // The row opened at the start, filled in. Falling back to an insert keeps a run
            // whose opening failed from losing its history as well — one lost row is a gap,
            // two are a pattern nobody can read.
            long id = runId.isPresent()
                    ? close(runId.getAsLong(), report, effectiveModel(scoreModel), awaiting)
                    : insert(report, startedAt, effectiveModel(scoreModel), awaiting);
            for (Map.Entry<FilterStage, Integer> stage :
                    report.filtered().removed().entrySet()) {
                jdbc.sql(INSERT_STAGE)
                        .params(id, stage.getKey().name(), stage.getValue())
                        .update();
            }
            // Where the time went. Written here rather than as each stage finishes, because
            // the row they reference does not exist until the run is over — the history row
            // is deliberately the last thing a run writes.
            for (StageTiming timing : stages) {
                jdbc.sql(INSERT_TIMING)
                        .params(
                                id,
                                timing.position(),
                                timing.stage(),
                                java.sql.Timestamp.from(timing.startedAt()),
                                java.sql.Timestamp.from(timing.endedAt()),
                                timing.status(),
                                timing.note())
                        .update();
            }
        } catch (RuntimeException e) {
            log.error("The run was not recorded: {}", e.getMessage(), e);
        }
    }

    /**
     * The second half of a batched run, once the collector has packaged and written.
     *
     * <p>`scored` is added rather than replaced: the run itself may have scored some offers
     * directly and submitted the rest, and the two together are what the run did. The two
     * band counts are read from the table instead of being passed in — they are standing
     * totals in the report too, and the batch collection does not carry them.
     */
    public void complete(int scored, int packaged, boolean digestWritten) {
        try {
            int updated = jdbc.sql(COMPLETE_AWAITING)
                    .params(packaged, digestWritten, scored)
                    .update();
            if (updated == 0) {
                // Not an error: a collector polling on an empty queue finds nothing to
                // complete, and a run recorded before this table existed has no row.
                log.debug("No run was waiting for a batch, so nothing was completed");
            }
        } catch (RuntimeException e) {
            log.error("The batched run was not completed in the history: {}", e.getMessage(), e);
        }
    }

    /**
     * Fills in the row the run opened with, and stamps {@code finished_at}.
     *
     * <p>{@code started_at} is deliberately not touched: it is what bounds this run's source
     * rows, and rewriting it here would move a boundary the reporting query has already
     * read. An {@code AWAITING_BATCH} row gets a {@code finished_at} too — the collector
     * moves it forward later, which is exactly why the next run's {@code started_at}, and
     * not this column, is the upper bound of a run's window.
     */
    private long close(long runId, IngestReport report, String scoreModel, boolean awaiting) {
        var filtered = report.filtered();
        var enriched = report.enriched();
        var scored = report.scored();
        int updated = jdbc.sql(CLOSE)
                .params(
                        String.valueOf(config.snapshot().rules().version()),
                        scoreModel,
                        awaiting ? "AWAITING_BATCH" : "COMPLETE",
                        report.sources().stream()
                                .mapToInt(source -> source.details().size())
                                .sum(),
                        report.extracted(),
                        report.written(),
                        report.merged(),
                        filtered.considered(),
                        filtered.passed(),
                        enriched.considered(),
                        enriched.enriched(),
                        enriched.incomplete(),
                        enriched.fromCache(),
                        enriched.requests(),
                        scored.considered(),
                        scored.scored(),
                        scored.unscored(),
                        scored.shortlisted(),
                        scored.review(),
                        scored.submitted(),
                        awaiting ? 0 : report.packaged().built(),
                        !awaiting && report.digest() != null,
                        runId)
                .update();
        if (updated == 0) {
            throw new IllegalStateException("the run row " + runId + " was gone before it could be closed");
        }
        return runId;
    }

    private long insert(IngestReport report, Instant startedAt, String scoreModel, boolean awaiting) {
        var filtered = report.filtered();
        var enriched = report.enriched();
        var scored = report.scored();
        OptionalLong id = jdbc.sql(INSERT)
                .params(
                        java.sql.Timestamp.from(startedAt),
                        String.valueOf(config.snapshot().rules().version()),
                        scoreModel,
                        awaiting ? "AWAITING_BATCH" : "COMPLETE",
                        report.sources().stream()
                                .mapToInt(source -> source.details().size())
                                .sum(),
                        report.extracted(),
                        report.written(),
                        report.merged(),
                        filtered.considered(),
                        filtered.passed(),
                        enriched.considered(),
                        enriched.enriched(),
                        enriched.incomplete(),
                        enriched.fromCache(),
                        enriched.requests(),
                        scored.considered(),
                        scored.scored(),
                        scored.unscored(),
                        scored.shortlisted(),
                        scored.review(),
                        scored.submitted(),
                        awaiting ? 0 : report.packaged().built(),
                        !awaiting && report.digest() != null)
                .query(Long.class)
                .optional()
                .map(OptionalLong::of)
                .orElse(OptionalLong.empty());
        return id.orElseThrow(() -> new IllegalStateException("the insert returned no id"));
    }
}
