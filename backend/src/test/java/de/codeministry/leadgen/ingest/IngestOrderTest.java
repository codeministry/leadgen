/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.ingest;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.codeministry.leadgen.analytics.PipelineRunRecorder;
import de.codeministry.leadgen.archive.ArchiveReport;
import de.codeministry.leadgen.archive.ArchiveService;
import de.codeministry.leadgen.config.ConfigRegistry;
import de.codeministry.leadgen.config.ConfigSnapshot;
import de.codeministry.leadgen.config.model.SourcesConfig;
import de.codeministry.leadgen.dedupe.DeduplicationService;
import de.codeministry.leadgen.digest.DigestService;
import de.codeministry.leadgen.enrich.EnrichmentReport;
import de.codeministry.leadgen.enrich.EnrichmentService;
import de.codeministry.leadgen.filter.FilterReport;
import de.codeministry.leadgen.filter.FilterService;
import de.codeministry.leadgen.ingest.connector.SourceConnector;
import de.codeministry.leadgen.ingest.extract.HtmlBlockExtractor;
import de.codeministry.leadgen.ingest.extract.MarkdownExtractor;
import de.codeministry.leadgen.ingest.extract.OfferMapper;
import de.codeministry.leadgen.ingest.store.OfferStore;
import de.codeministry.leadgen.packaging.PackageReport;
import de.codeministry.leadgen.packaging.PackagingService;
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
    private final ScoringService scoring = mock(ScoringService.class);
    private final PackagingService packaging = mock(PackagingService.class);
    private final DigestService digest = mock(DigestService.class);
    private final PipelineRunRecorder history = mock(PipelineRunRecorder.class);
    private final SourceConnector connector = mock(SourceConnector.class);

    private IngestService service;

    @BeforeEach
    void wire() {
        // No sources at all: this test is about the seven global stages, and a source would
        // only add a second reason for a call to happen.
        var snapshot = mock(ConfigSnapshot.class);
        var sources = mock(SourcesConfig.class);
        when(config.snapshot()).thenReturn(snapshot);
        when(snapshot.sources()).thenReturn(sources);
        when(sources.sources()).thenReturn(List.of());
        when(connector.type()).thenReturn("file");

        when(filter.run()).thenReturn(new FilterReport(Map.of(), 0, 0));
        when(archive.run()).thenReturn(new ArchiveReport(0, 0, 0, 0));
        when(enrich.run()).thenReturn(new EnrichmentReport(0, 0, 0, 0, 0));
        when(scoring.run(any())).thenReturn(new ScoringReport(0, 0, 0, 0, 0, 0));
        when(packaging.run()).thenReturn(new PackageReport(0, 0, 0, List.of()));
        when(digest.render(any())).thenReturn(Optional.empty());

        service = new IngestService(
                config,
                List.of(connector),
                mock(HtmlBlockExtractor.class),
                mock(MarkdownExtractor.class),
                mock(OfferMapper.class),
                mock(OfferStore.class),
                dedupe,
                filter,
                archive,
                enrich,
                scoring,
                packaging,
                digest,
                history);
    }

    @Test
    void runsTheStagesInTheOrderTheirPlacementIsArguedFor() {
        service.run("some-model");

        var order = inOrder(scoring, dedupe, filter, archive, enrich, packaging, digest, history);
        // The model check is first because scoring is last: checked only where it is used,
        // an unknown name is refused after a whole pass has already been paid for.
        order.verify(scoring).checkModel("some-model");
        order.verify(dedupe).run();
        order.verify(filter).run();
        order.verify(archive).run();
        order.verify(enrich).run();
        order.verify(scoring).run("some-model");
        order.verify(packaging).run();
        order.verify(digest).render(any());
        order.verify(history).record(any(), any(), anyString(), any());
        order.verifyNoMoreInteractions();
    }

    @Test
    void refusesAnUnknownModelBeforeReadingASingleSource() {
        doThrow(new IllegalArgumentException("unknown model")).when(scoring).checkModel("nonsense");

        assertThatThrownBy(() -> service.run("nonsense")).isInstanceOf(IllegalArgumentException.class);

        // `connector.type()` is called once when the map is built in the constructor, so
        // the assertion is about reading, not about touching the object at all.
        verify(connector, never()).read(any(), org.mockito.ArgumentMatchers.anyLong());
        verify(dedupe, never()).run();
        verify(history, never()).record(any(), any(), anyString(), any());
    }
}
