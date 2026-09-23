/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.analytics;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.codeministry.leadgen.application.OpenReport;
import de.codeministry.leadgen.archive.ArchiveReport;
import de.codeministry.leadgen.config.ConfigRegistry;
import de.codeministry.leadgen.content.ContentReport;
import de.codeministry.leadgen.enrich.EnrichmentReport;
import de.codeministry.leadgen.fields.FieldsReport;
import de.codeministry.leadgen.filter.FilterReport;
import de.codeministry.leadgen.ingest.IngestReport;
import de.codeministry.leadgen.packaging.PackageReport;
import de.codeministry.leadgen.retrieval.RetrievalReport;
import de.codeministry.leadgen.score.Judges;
import de.codeministry.leadgen.score.ScoringReport;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/**
 * The recorder cannot take a run down with it.
 *
 * <p>`IngestService.run` calls this last and does not guard the call, which is deliberate and
 * only safe because the guarantee lives here: <b>a history row is worth less than the run</b>.
 * A pass that found, filtered, scored and packaged a morning's offers must not be lost
 * because a metrics table was unavailable.
 *
 * <p>Pinned as a unit test with a database that refuses to hand out a connection at all,
 * because that is the failure with no warning: every query in the method throws the same
 * `DataAccessException`, and nothing above catches it.
 */
class PipelineRunRecorderTest {

    private static final IngestReport REPORT = new IngestReport(
            List.of(),
            0,
            new FilterReport(Map.of(), 0, 0),
            new ArchiveReport(0, 0, 0, 0),
            new EnrichmentReport(0, 0, 0, 0, 0, 0),
            ContentReport.skipped(),
            FieldsReport.skipped(),
            new ScoringReport(0, 0, 0, 0, 0, 0, 0),
            RetrievalReport.skipped(),
            null,
            OpenReport.nothing(),
            new PackageReport(0, 0, 0, List.of()),
            Instant.EPOCH);

    @Test
    void swallowsADatabaseThatWillNotAnswerRatherThanEndingTheRun() throws SQLException {
        DataSource broken = mock(DataSource.class);
        when(broken.getConnection()).thenThrow(new SQLException("the history table is gone"));

        var recorder = new PipelineRunRecorder(broken, mock(ConfigRegistry.class), mock(Judges.class));

        assertThatCode(() ->
                        recorder.record(java.util.OptionalLong.empty(), REPORT, Instant.now(), "some-model", List.of()))
                .doesNotThrowAnyException();
    }

    @Test
    void swallowsADatabaseThatWillNotAnswerWhenRecordingAFailureToo() throws SQLException {
        // The failure path is called from a catch block that is about to rethrow. A recorder
        // throwing there would replace the stage's own exception with a database one, and
        // the sentence the operator reads would name the wrong problem.
        DataSource broken = mock(DataSource.class);
        when(broken.getConnection()).thenThrow(new SQLException("the history table is gone"));

        var recorder = new PipelineRunRecorder(broken, mock(ConfigRegistry.class), mock(Judges.class));

        assertThatCode(() -> recorder.recordFailure(
                        java.util.OptionalLong.empty(),
                        REPORT,
                        Instant.now(),
                        "some-model",
                        List.of(new StageTiming(
                                0, "DEDUPE", Instant.EPOCH, Instant.EPOCH, StageTiming.FAILED, "gone"))))
                .doesNotThrowAnyException();
    }
}
