package de.codeministry.leadgen.analytics;

import de.codeministry.leadgen.config.ConfigRegistry;
import de.codeministry.leadgen.filter.FilterStage;
import de.codeministry.leadgen.ingest.IngestReport;
import de.codeministry.leadgen.score.Judges;
import java.time.Instant;
import java.util.Map;
import java.util.OptionalLong;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

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

    private static final String INSERT =
            """
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

    private static final String INSERT_STAGE =
            "INSERT INTO pipeline_run_stage (run_id, stage, removed) VALUES (?, ?, ?)";

    /**
     * A batched run is finished by the collector, not by the request that started it, so
     * the row it left behind is the one to complete. Ordered and limited because batching
     * is on for a run or it is not, and there is one operator: two rows awaiting a batch at
     * once would mean two runs overlapping, which nothing here can produce.
     */
    private static final String COMPLETE_AWAITING =
            """
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

    /** The run that just finished, or that is waiting for its batch. */
    public void record(IngestReport report, Instant startedAt, String scoreModel) {
        try {
            // A run that submitted a batch has not packaged anything yet and has not
            // written a digest. Recorded as COMPLETE it would state a shortlist belonging
            // to the previous run, and look entirely normal doing it.
            boolean awaiting = report.scored().submitted() > 0;
            long id = insert(report, startedAt, effectiveModel(scoreModel), awaiting);
            for (Map.Entry<FilterStage, Integer> stage : report.filtered().removed().entrySet()) {
                jdbc.sql(INSERT_STAGE)
                        .params(id, stage.getKey().name(), stage.getValue())
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
