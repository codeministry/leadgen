/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.web;

import de.codeministry.leadgen.analytics.LastRunQueryService;
import de.codeministry.leadgen.analytics.LastRunSource;
import de.codeministry.leadgen.analytics.LastRunView;
import de.codeministry.leadgen.archive.ArchiveReport;
import de.codeministry.leadgen.enrich.EnrichmentReport;
import de.codeministry.leadgen.filter.FilterReport;
import de.codeministry.leadgen.ingest.DocumentIngestResult;
import de.codeministry.leadgen.ingest.IngestReport;
import de.codeministry.leadgen.ingest.IngestService;
import de.codeministry.leadgen.ingest.SourceIngestResult;
import de.codeministry.leadgen.packaging.PackageReport;
import de.codeministry.leadgen.score.ScoringReport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/**
 * What the run endpoints actually put on the wire.
 *
 * <p>Both cases here are silent when they break. A missing `complete` flag turns the
 * announced-versus-extracted check into an alarm that fires on every healthy run, and a
 * 404 for "nothing has run yet" is indistinguishable, in the browser, from an endpoint
 * that does not exist.
 */
@WebMvcTest(IngestController.class)
class IngestControllerTest {

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private IngestService ingest;

    @MockitoBean
    private LastRunQueryService lastRun;

    @Test
    void answersWithNoContentWhenNothingHasEverRun() {
        given(lastRun.lastRun()).willReturn(Optional.empty());

        assertThat(mvc.get().uri("/api/ingest/last")).hasStatus(204);
    }

    @Test
    void carriesTheDerivedCompleteFlagOfARecordedRun() {
        // Jackson builds a record's JSON from its components, and `complete()` is not one.
        // Without `@JsonProperty` the field is simply absent, the browser reads `undefined`
        // and `!complete` is true for every source that announced anything at all.
        given(lastRun.lastRun())
                .willReturn(Optional.of(new LastRunView(
                        Instant.parse("2026-09-02T04:12:00Z"),
                        "COMPLETE",
                        "some-model",
                        169,
                        151,
                        18,
                        Map.of("ABROAD", 13),
                        169,
                        73,
                        67,
                        7,
                        13,
                        7,
                        true,
                        List.of(new LastRunSource("demo-newsletter", 5, 169, 151, 169)))));

        assertThat(mvc.get().uri("/api/ingest/last"))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.sources[0].complete")
                .isEqualTo(true);
    }

    @Test
    void carriesTheDerivedCompleteFlagOfADocumentInARun() {
        // The same defect one level down, and the one that was actually shipped: measured
        // on 2026-09-02, five documents whose announced count matched exactly were all
        // reported as mismatches, because the flag never reached the browser.
        given(ingest.run(null))
                .willReturn(new IngestReport(
                        List.of(new SourceIngestResult(
                                "demo-newsletter",
                                1,
                                31,
                                31,
                                List.of(new DocumentIngestResult("2026-08-25.eml", 31, 31)))),
                        18,
                        new FilterReport(Map.of(), 12, 31),
                        new ArchiveReport(0, 0, 0, 0),
                        new EnrichmentReport(12, 0, 12, 0, 0, 0),
                        new ScoringReport(12, 12, 0, 2, 3, 0),
                        null,
                        new PackageReport(2, 2, 0, List.of()),
                        Instant.parse("2026-09-05T06:12:00Z")));

        assertThat(mvc.post().uri("/api/ingest"))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.sources[0].details[0].complete")
                .isEqualTo(true);
    }

    @Test
    void answers409WhenAPassIsAlreadyRunning() throws Exception {
        // Not 429 and not a queued 202: the request is refused because of the state of the
        // resource, and nothing is queued — a second pass is the same work done twice. The
        // CronJob's curl exits non-zero on this, which is the honest record of a night that
        // skipped rather than a night that ran twice.
        given(ingest.run(null)).willThrow(new IngestService.AlreadyRunning());

        assertThat(mvc.post().uri("/api/ingest"))
                .hasStatus(org.springframework.http.HttpStatus.CONFLICT)
                .bodyText()
                .contains("already in progress");
    }
}
