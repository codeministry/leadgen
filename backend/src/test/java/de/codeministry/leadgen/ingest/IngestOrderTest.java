/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.ingest;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

import de.codeministry.leadgen.analytics.PipelineRunRecorder;
import de.codeministry.leadgen.analytics.StageTiming;
import de.codeministry.leadgen.application.ApplicationService;
import de.codeministry.leadgen.application.OpenReport;
import de.codeministry.leadgen.archive.ArchiveReport;
import de.codeministry.leadgen.archive.ArchiveService;
import de.codeministry.leadgen.config.ConfigRegistry;
import de.codeministry.leadgen.config.ConfigSnapshot;
import de.codeministry.leadgen.config.model.SourcesConfig;
import de.codeministry.leadgen.content.ContentReport;
import de.codeministry.leadgen.content.ContentService;
import de.codeministry.leadgen.dedupe.DeduplicationService;
import de.codeministry.leadgen.digest.DigestService;
import de.codeministry.leadgen.enrich.EnrichmentReport;
import de.codeministry.leadgen.enrich.EnrichmentService;
import de.codeministry.leadgen.fields.FieldsReport;
import de.codeministry.leadgen.fields.FieldsService;
import de.codeministry.leadgen.filter.FilterReport;
import de.codeministry.leadgen.filter.FilterService;
import de.codeministry.leadgen.ingest.connector.SourceConnector;
import de.codeministry.leadgen.ingest.extract.HtmlBlockExtractor;
import de.codeministry.leadgen.ingest.extract.LlmDocumentExtractor;
import de.codeministry.leadgen.ingest.extract.MarkdownExtractor;
import de.codeministry.leadgen.ingest.extract.OfferMapper;
import de.codeministry.leadgen.ingest.store.OfferStore;
import de.codeministry.leadgen.packaging.PackageReport;
import de.codeministry.leadgen.packaging.PackagingService;
import de.codeministry.leadgen.retrieval.RetrievalIndexService;
import de.codeministry.leadgen.retrieval.RetrievalReport;
import de.codeministry.leadgen.score.ScoringReport;
import de.codeministry.leadgen.score.ScoringService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The order of the pipeline, pinned as a contract rather than as a comment.
 *
 * <p>Every stage's placement in `IngestService.run` is argued for in a comment beside it, and
 * every one of those arguments is about cost or correctness: deduplication after all sources
 * because a pass scoped to one would never see the pair it exists to collapse; the archive
 * before the two stages that cost money; the digest after packaging so it can say which
 * offers already have a folder; the history row last because a run that failed halfway must
 * not leave a row claiming a clean pass.
 *
 * <p>A comment cannot fail a build. This can, which is why it exists before any attempt to
 * move the orchestration into a framework.
 */
class IngestOrderTest {

    private final ConfigRegistry config = mock(ConfigRegistry.class);
    private final DeduplicationService dedupe = mock(DeduplicationService.class);
    private final FilterService filter = mock(FilterService.class);
    private final ArchiveService archive = mock(ArchiveService.class);
    private final EnrichmentService enrich = mock(EnrichmentService.class);
    private final ContentService content = mock(ContentService.class);
    private final FieldsService fields = mock(FieldsService.class);
    private final ScoringService scoring = mock(ScoringService.class);
    private final RetrievalIndexService retrieval = mock(RetrievalIndexService.class);
    private final ApplicationService applications = mock(ApplicationService.class);
    private final PackagingService packaging = mock(PackagingService.class);
    private final DigestService digest = mock(DigestService.class);
    private final PipelineRunRecorder history = mock(PipelineRunRecorder.class);
    private final SourceConnector connector = mock(SourceConnector.class);

    private IngestService service;

    @BeforeEach
    void wire() {
        // No sources at all: this test is about the global stages, and a source would
        // only add a second reason for a call to happen.
        var snapshot = mock(ConfigSnapshot.class);
        var sources = mock(SourcesConfig.class);
        when(config.snapshot()).thenReturn(snapshot);
        when(snapshot.sources()).thenReturn(sources);
        when(sources.sources()).thenReturn(List.of());
        when(connector.type()).thenReturn("file");

        when(filter.run()).thenReturn(new FilterReport(Map.of(), 0, 0));
        when(archive.run()).thenReturn(new ArchiveReport(0, 0, 0, 0));
        when(enrich.run()).thenReturn(new EnrichmentReport(0, 0, 0, 0, 0, 0));
        when(content.run()).thenReturn(ContentReport.skipped());
        when(fields.run()).thenReturn(FieldsReport.skipped());
        when(scoring.run(any())).thenReturn(new ScoringReport(0, 0, 0, 0, 0, 0, 0));
        when(retrieval.run()).thenReturn(RetrievalReport.skipped());
        when(applications.openShortlisted()).thenReturn(OpenReport.nothing());
        when(packaging.run()).thenReturn(new PackageReport(0, 0, 0, List.of()));
        when(digest.render(any())).thenReturn(Optional.empty());

        service = new IngestService(
                config,
                List.of(connector),
                mock(HtmlBlockExtractor.class),
                mock(MarkdownExtractor.class),
                mock(LlmDocumentExtractor.class),
                mock(OfferMapper.class),
                mock(OfferStore.class),
                dedupe,
                filter,
                archive,
                enrich,
                content,
                fields,
                scoring,
                retrieval,
                applications,
                packaging,
                digest,
                history);
    }

    @Test
    void refusesASecondPassWhileOneIsRunningRatherThanQueueingBehindIt() throws Exception {
        // Every stage rewrites the same table — the filter writes a verdict on every row
        // with no WHERE — so two passes contend for the same rows and the loser waits.
        // Measured on the cluster before this guard existed: two filter updates blocked for
        // thirteen minutes behind a scoring transaction open for twenty, with a third run
        // stacked behind those. The CronJob's `concurrencyPolicy: Forbid` does not cover
        // this, because it only governs the jobs the CronJob itself creates — and the
        // button is how a run is normally started.
        var inTheMiddle = new java.util.concurrent.CountDownLatch(1);
        var mayFinish = new java.util.concurrent.CountDownLatch(1);
        when(scoring.run(any())).thenAnswer(invocation -> {
            inTheMiddle.countDown();
            mayFinish.await(5, java.util.concurrent.TimeUnit.SECONDS);
            return new ScoringReport(0, 0, 0, 0, 0, 0, 0);
        });

        var first = java.util.concurrent.CompletableFuture.runAsync(() -> service.run(null));
        assertThat(inTheMiddle.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

        assertThatThrownBy(() -> service.run(null))
                .isInstanceOf(IngestService.AlreadyRunning.class)
                .hasMessageContaining("already in progress");

        mayFinish.countDown();
        first.get(5, java.util.concurrent.TimeUnit.SECONDS);

        // And the lock is released, so the next pass is not refused forever.
        assertThatCode(() -> service.run(null)).doesNotThrowAnyException();
    }

    @Test
    void runsTheStagesInTheOrderTheirPlacementIsArguedFor() {
        service.run("some-model");

        var order = inOrder(
                scoring, dedupe, filter, archive, enrich, content, fields, applications, packaging, digest, history);
        // The model check is first because scoring is last: checked only where it is used,
        // an unknown name is refused after a whole pass has already been paid for.
        order.verify(scoring).checkModel("some-model");
        order.verify(dedupe).run();
        order.verify(filter).run();
        order.verify(archive).run();
        order.verify(enrich).run();
        order.verify(content).run();
        // Between the two, and both halves matter: after content because it reads the advert
        // the content stage left rather than the page around it, and before scoring because
        // what it writes feeds `project_setup` and the judge's description of an offer.
        order.verify(fields).run();
        order.verify(scoring).run("some-model");
        // The board before the folder: reaching the shortlist buys a card, and the folder
        // waits for somebody to agree with it.
        order.verify(applications).openShortlisted();
        order.verify(packaging).run();
        order.verify(digest).render(any());
        order.verify(history).record(any(), any(), any(), anyString(), any());
        order.verifyNoMoreInteractions();
    }

    @Test
    void marksEveryStageOnTheOpenRowAsItEntersIt() {
        // A run takes eleven minutes on the deployed corpus, and until this existed nothing on
        // any screen said what it was doing. The marker writes the open row as each stage
        // starts; the per-stage table is still written after the work, because that one is the
        // record and this one is only worth something while the run is still going.
        given(history.start(any(), any(), anyInt())).willReturn(java.util.OptionalLong.of(77L));

        service.run(null);

        var positions = org.mockito.ArgumentCaptor.forClass(Integer.class);
        var names = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(history, atLeastOnce()).mark(org.mockito.ArgumentMatchers.eq(77L), positions.capture(), names.capture());

        // No sources are configured here, so what is left is exactly the global stages — and
        // the count `IngestService` writes into the row has to be that same number, or the
        // progress stops one short of the end for a month before anybody notices.
        // RETRIEVAL sits behind SCORE and not behind CONTENT, where it reads: `LlmBudget` is
        // one allowance shared by every stage, and the first pass after the stage is switched
        // on walks the whole working list. In front of the judge that backfill spends the day
        // and the shortlist goes unjudged.
        assertThat(names.getAllValues())
                .containsExactly(
                        "DEDUPE",
                        "FILTER",
                        "ARCHIVE",
                        "ENRICH",
                        "CONTENT",
                        "FIELDS",
                        "SCORE",
                        "RETRIEVAL",
                        "OPEN",
                        "PACKAGE",
                        "DIGEST");
        assertThat(names.getAllValues()).hasSize(IngestService.GLOBAL_STAGES);
        // The workflow view is held against the list, not against the run; this is what ties the
        // two by name, so a renamed stage fails here rather than drifting out of the rules screen.
        assertThat(names.getAllValues()).containsExactlyElementsOf(IngestService.GLOBAL_STAGE_NAMES);
        assertThat(positions.getAllValues()).containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11);
        verify(history).start(any(), any(), org.mockito.ArgumentMatchers.eq(IngestService.GLOBAL_STAGES));
    }

    @Test
    void refusesAnUnknownModelBeforeReadingASingleSource() {
        doThrow(new IllegalArgumentException("unknown model")).when(scoring).checkModel("nonsense");

        assertThatThrownBy(() -> service.run("nonsense")).isInstanceOf(IllegalArgumentException.class);

        // `connector.type()` is called once when the map is built in the constructor, so
        // the assertion is about reading, not about touching the object at all.
        verify(connector, never()).read(any(), org.mockito.ArgumentMatchers.anyLong());
        verify(dedupe, never()).run();
        verify(history, never()).record(any(), any(), any(), anyString(), any());
        verify(history, never()).recordFailure(any(), any(), any(), anyString(), any());
        // Not even opened: the model check is the first statement in the run, before the
        // row that would otherwise sit there saying RUNNING for a pass that never began.
        verify(history, never()).start(any(), anyString(), anyInt());
    }

    @Test
    void endsAsFailedInTheStageThatThrewAndReleasesTheLock() {
        // Until this existed a stage that threw left the row RUNNING until the next start
        // closed it as ABANDONED with zeros, and the timings — the one thing that says which
        // stage — went down with the exception. V14 promised the opposite: "a stage that
        // threw still gets a row".
        when(filter.run()).thenReturn(new FilterReport(Map.of(), 12, 31));
        when(enrich.run()).thenThrow(new IllegalStateException("portal down"));

        assertThatThrownBy(() -> service.run("some-model"))
                .isInstanceOf(IngestService.StageFailed.class)
                .hasMessageContaining("ENRICH")
                .hasMessageContaining("portal down")
                .hasCauseInstanceOf(IllegalStateException.class);

        org.mockito.ArgumentCaptor<IngestReport> report = org.mockito.ArgumentCaptor.captor();
        org.mockito.ArgumentCaptor<List<StageTiming>> timings = org.mockito.ArgumentCaptor.captor();
        verify(history)
                .recordFailure(
                        any(),
                        report.capture(),
                        any(),
                        org.mockito.ArgumentMatchers.eq("some-model"),
                        timings.capture());
        verify(history, never()).record(any(), any(), any(), anyString(), any());
        // It stopped there: nothing behind the stage that threw ran.
        verify(content, never()).run();
        verify(scoring, never()).run(anyString());
        verify(digest, never()).render(any());
        // The counts up to that stage, and for what it never reached the same value the
        // stage answers when it is switched off.
        assertThat(report.getValue().filtered().passed()).isEqualTo(12);
        assertThat(report.getValue().enriched()).isEqualTo(EnrichmentReport.skipped());
        assertThat(report.getValue().packaged()).isEqualTo(PackageReport.nothing());
        assertThat(report.getValue().digest()).isNull();
        // Every timing, the failed one last and carrying the reason.
        assertThat(timings.getValue())
                .extracting(StageTiming::stage)
                .containsExactly("DEDUPE", "FILTER", "ARCHIVE", "ENRICH");
        assertThat(timings.getValue().getLast().status()).isEqualTo(StageTiming.FAILED);
        assertThat(timings.getValue().getLast().note()).isEqualTo("portal down");

        // And the lock is released, so the next pass is not refused forever. `doReturn`,
        // because `when(enrich.run())` would call the stub that still throws.
        doReturn(new EnrichmentReport(0, 0, 0, 0, 0, 0)).when(enrich).run();
        assertThatCode(() -> service.run("some-model")).doesNotThrowAnyException();
    }
}
