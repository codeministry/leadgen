/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.analytics;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The most recent run, read back from the two tables that survive it.
 *
 * <p>Read-only and separate from {@link AnalyticsQueryService} because it answers a
 * different question. That one draws a trend over thirty runs; this one answers "what
 * happened the last time this thing ran", which is what somebody opening the dashboard in
 * the morning is actually asking.
 *
 * <p>It cannot be derived from the {@code offer} table, and that is the whole reason
 * {@code pipeline_run} exists: the filter re-judges every offer on every pass and
 * overwrites its verdict, and the scorer overwrites its columns and deletes its reasons.
 * What a run did is destroyed by the next one.
 */
@Service
public class LastRunQueryService {

    /**
     * The newest run, by {@code started_at} rather than by {@code finished_at}.
     *
     * <p>Not interchangeable. A batched run is finished by the collector minutes or hours
     * later, which moves its {@code finished_at} past runs that started after it — order by
     * that column and a run from this morning can outrank one from this afternoon. When a
     * run starts is a fact about the run; when it finishes is a fact about the batch queue.
     *
     * <p>{@code id} breaks the tie, because nothing in the schema stops two rows sharing a
     * start instant and "the newest run" has to be one row.
     */
    private static final String LAST_RUN =
            """
            SELECT id, started_at, finished_at, status, score_model,
                   extracted, written, merged,
                   filter_considered, filter_passed,
                   scored, shortlisted, review, packaged, digest_written
            FROM pipeline_run
                    WHERE finished_at IS NOT NULL
            ORDER BY started_at DESC, id DESC
            LIMIT 1
            """;

    private static final String STAGES =
            "SELECT stage, removed FROM pipeline_run_stage WHERE run_id = :id ORDER BY stage";

    /**
     * The source rows of that run, addressed by time because they carry no run id.
     *
     * <p>{@code source_run} predates {@code pipeline_run} and has no foreign key to it, so
     * the window is the join. A lower bound alone used to be called exact, on the reasoning
     * that "no later run exists to contribute rows above it" — which holds only while
     * nothing else is running. Measured on the cluster: the panel listed every source twice,
     * because a pass begun two minutes after the reported one had already written its rows
     * and they fell inside an unbounded window.
     *
     * <p>The upper bound is the <b>next run's {@code started_at}</b>, and that column is the
     * right one precisely because it never moves. {@code finished_at} would be the wrong
     * fix, as it always was: a batched run's is pushed forward by the collector, so a window
     * closed on it would sweep in whatever ran in between. Since {@code V15} a run opens its
     * row when it starts, so the next run's start is knowable even while it is still going.
     *
     * <p>Joined to {@code source} for the name, which is the id the report and the screens
     * speak in. The numeric key is the database's business.
     */
    private static final String SOURCES =
            """
            SELECT s.name AS source_id, r.documents, r.extracted, r.written, r.announced
            FROM source_run r
            JOIN source s ON s.id = r.source_id
            WHERE r.ran_at >= :startedAt
                      AND r.ran_at < COALESCE(
                            (SELECT min(started_at) FROM pipeline_run WHERE started_at > :startedAt),
                            'infinity'::timestamptz)
            ORDER BY s.name
            """;

    private final JdbcClient jdbc;

    LastRunQueryService(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    /** Empty when nothing has ever run, which is a state and not an error. */
    public Optional<LastRunView> lastRun() {
        // The row is read out whole before the two follow-up queries run. Issuing them from
        // inside the row mapper would hold this ResultSet open while borrowing a second
        // connection for each one — it works until the pool is the size it is in a
        // container, and then it deadlocks under exactly the load nobody tests with.
        Optional<Row> row = jdbc.sql(LAST_RUN)
                .query((rs, index) -> new Row(
                        rs.getLong("id"),
                        // getTimestamp().toInstant(), never getObject(.., Instant.class):
                        // the Postgres driver refuses the direct mapping for timestamptz
                        // and throws a DataIntegrityViolationException naming the whole
                        // query rather than the column it choked on.
                        rs.getTimestamp("started_at"),
                        rs.getTimestamp("finished_at").toInstant(),
                        rs.getString("status"),
                        rs.getString("score_model"),
                        rs.getInt("extracted"),
                        rs.getInt("written"),
                        rs.getInt("merged"),
                        rs.getInt("filter_considered"),
                        rs.getInt("filter_passed"),
                        rs.getInt("scored"),
                        rs.getInt("shortlisted"),
                        rs.getInt("review"),
                        rs.getInt("packaged"),
                        rs.getBoolean("digest_written")))
                .optional();

        return row.map(run -> new LastRunView(
                run.finishedAt(),
                run.status(),
                run.scoreModel(),
                run.extracted(),
                run.written(),
                run.merged(),
                stagesOf(run.id()),
                run.filterConsidered(),
                run.filterPassed(),
                run.scored(),
                run.shortlisted(),
                run.review(),
                run.packaged(),
                run.digestWritten(),
                sourcesSince(run.startedAt())));
    }

    /** The row as it stands in the table, so the ResultSet can be closed before the rest. */
    private record Row(
            long id,
            Timestamp startedAt,
            Instant finishedAt,
            String status,
            String scoreModel,
            int extracted,
            int written,
            int merged,
            int filterConsidered,
            int filterPassed,
            int scored,
            int shortlisted,
            int review,
            int packaged,
            boolean digestWritten) {}

    /** Insertion-ordered, so the stages arrive in the order the SQL sorted them. */
    private Map<String, Integer> stagesOf(long runId) {
        Map<String, Integer> removed = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> stage : jdbc.sql(STAGES)
                .param("id", runId)
                .query((rs, index) -> Map.entry(rs.getString("stage"), rs.getInt("removed")))
                .list()) {
            removed.put(stage.getKey(), stage.getValue());
        }
        return removed;
    }

    private List<LastRunSource> sourcesSince(Timestamp startedAt) {
        return jdbc.sql(SOURCES)
                .param("startedAt", startedAt)
                .query((rs, index) -> {
                    // Read last and checked immediately: `wasNull` speaks about the most
                    // recent column read, so one more `getInt` between the two would make
                    // it answer about that one instead. Null is not zero here — it means
                    // the source states no count to check against.
                    String sourceId = rs.getString("source_id");
                    int documents = rs.getInt("documents");
                    int extracted = rs.getInt("extracted");
                    int written = rs.getInt("written");
                    int announced = rs.getInt("announced");
                    return new LastRunSource(sourceId, documents, extracted, written, rs.wasNull() ? null : announced);
                })
                .list();
    }
}
