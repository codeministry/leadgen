/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.analytics;

import de.codeministry.leadgen.archive.ArchiveReport;
import de.codeministry.leadgen.config.ConfigRegistry;
import de.codeministry.leadgen.enrich.EnrichmentReport;
import de.codeministry.leadgen.filter.FilterReport;
import de.codeministry.leadgen.ingest.IngestReport;
import de.codeministry.leadgen.packaging.PackageReport;
import de.codeministry.leadgen.score.Judges;
import de.codeministry.leadgen.score.ScoringReport;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
            new ScoringReport(0, 0, 0, 0, 0, 0),
            null,
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
}
