/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.ingest;

import de.codeministry.leadgen.analytics.PipelineRunRecorder;
import de.codeministry.leadgen.analytics.StageLog;
import de.codeministry.leadgen.archive.ArchiveService;
import de.codeministry.leadgen.config.ConfigRegistry;
import de.codeministry.leadgen.config.model.SourcesConfig.Source;
import de.codeministry.leadgen.dedupe.DeduplicationService;
import de.codeministry.leadgen.digest.DigestService;
import de.codeministry.leadgen.enrich.EnrichmentService;
import de.codeministry.leadgen.filter.FilterService;
import de.codeministry.leadgen.ingest.connector.SourceConnector;
import de.codeministry.leadgen.ingest.extract.HtmlBlockExtractor;
import de.codeministry.leadgen.ingest.extract.MarkdownExtractor;
import de.codeministry.leadgen.ingest.extract.OfferMapper;
import de.codeministry.leadgen.ingest.store.OfferStore;
import de.codeministry.leadgen.packaging.PackagingService;
import de.codeministry.leadgen.score.ScoringService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Runs one pass over every enabled source: fetch, extract, store.
 *
 * <p>Extraction needs no language model here. The measured newsletter is structured
 * HTML and CSS covers every field, so `fallback: none` applies and the model is only
 * needed later, for scoring and writing. A source without usable structure will set
 * `fallback: llm` and get a different extractor — which is why the strategy is read from
 * the config rather than assumed.
 */
@Slf4j
@Service
public class IngestService {

    private static final String HTML_BLOCKS = "html-blocks";
    /** One document is one offer, and the frontmatter carries the eight fields. */
    private static final String MARKDOWN_FRONTMATTER = "markdown-frontmatter";

    private final ConfigRegistry config;
    private final Map<String, SourceConnector> connectors;
    private final HtmlBlockExtractor extractor;
    private final MarkdownExtractor markdown;
    private final OfferMapper mapper;
    private final OfferStore store;
    private final DeduplicationService dedupe;
    private final FilterService filter;
    private final ArchiveService archive;
    private final EnrichmentService enrich;
    private final ScoringService scoring;
    private final PackagingService packaging;
    private final DigestService digest;
    private final PipelineRunRecorder history;

    IngestService(
            ConfigRegistry config,
            List<SourceConnector> connectors,
            HtmlBlockExtractor extractor,
            MarkdownExtractor markdown,
            OfferMapper mapper,
            OfferStore store,
            DeduplicationService dedupe,
            FilterService filter,
            ArchiveService archive,
            EnrichmentService enrich,
            ScoringService scoring,
            PackagingService packaging,
            DigestService digest,
            PipelineRunRecorder history) {
        this.config = config;
        this.connectors = connectors.stream().collect(Collectors.toMap(SourceConnector::type, Function.identity()));
        this.extractor = extractor;
        this.markdown = markdown;
        this.mapper = mapper;
        this.store = store;
        this.dedupe = dedupe;
        this.filter = filter;
        this.archive = archive;
        this.enrich = enrich;
        this.scoring = scoring;
        this.packaging = packaging;
        this.digest = digest;
        this.history = history;
    }

    /** One pass with the configured default scoring model. */
    public IngestReport run() {
        return run(null);
    }

    /**
     * One pass over every enabled source.
     *
     * @param scoringModel which judge scores the survivors, or null for the configured
     *     default. It is a parameter of the run rather than a stored setting: the choice is
     *     made next to the button that starts the pass, and nothing about it outlives the
     *     request. See {@link ScoringService#run(String)} for what changing it costs.
     */
    public IngestReport run(String scoringModel) {
        // Before anything else, because scoring is the last stage: checked only there, a
        // name nobody configured is refused after the sources have been read, the
        // duplicates clustered, the filter applied and the surviving ads fetched from
        // their portals — a whole pass spent to answer that a model is unknown.
        scoring.checkModel(scoringModel);
        // Captured before anything runs: the row this ends in states how long the run
        // took, and `now()` at the end would state only when it stopped.
        var startedAt = java.time.Instant.now();
        // Where the time went, collected as the run goes and written with the history row at
        // the end. A run whose counts look ordinary can still have spent four minutes in
        // enrichment because one portal was slow, and nothing in the counts says so.
        var stages = new StageLog();
        List<SourceIngestResult> results = new ArrayList<>();

        for (Source source : config.snapshot().sources().sources()) {
            if (!source.enabled()) {
                continue;
            }
            SourceConnector connector = connectors.get(source.type());
            if (connector == null) {
                // Not fatal: a config may declare a source type a later step implements.
                log.warn("Source '{}' has type '{}', for which no connector exists yet", source.id(), source.type());
                continue;
            }
            try {
                // Timed per source rather than as one block: "ingest took four minutes" is
                // not actionable, "the mailbox took four minutes and the two file sources
                // took nothing" is.
                results.add(stages.time("INGEST " + source.id(), () -> ingest(source, connector)));
            } catch (IngestException e) {
                // One unreachable mailbox must not stop the file sources behind it.
                log.error("Source '{}' failed: {}", source.id(), e.getMessage(), e);
                results.add(new SourceIngestResult(source.id(), 0, 0, 0, List.of()));
            }
        }
        // After every source, never per source: the whole point is that one project
        // reaches the pipeline through several portals, so a pass scoped to one source
        // would never see the pair it exists to collapse. The hard filter follows, in
        // that order, because a cluster judged twice under two verdicts is worse than a
        // cluster judged once. Enrichment comes last and only touches what survived:
        // fetching a thousand ads to then discard eight hundred would be rude to the
        // portals and slow for nothing.
        var deduplicated = stages.time("DEDUPE", dedupe::run);
        var filtered = stages.time("FILTER", filter::run);
        // After the filter, so an offer somebody restores carries a current verdict; before
        // enrichment, because that is the stage that leaves the machine and scoring is the
        // one that costs money. An offer that has aged off the working list must pay for
        // neither.
        var archived = stages.time("ARCHIVE", archive::run);
        var enriched = stages.time("ENRICH", enrich::run);
        var scored = stages.time("SCORE", () -> scoring.run(scoringModel));
        // Packaging before the digest, so the digest can say which offers already have a
        // folder. Both write files and neither sends anything.
        var packages = stages.time("PACKAGE", packaging::run);
        var written = stages.time(
                "DIGEST", () -> digest.render(java.time.LocalDate.now()).orElse(null));
        var report = new IngestReport(results, deduplicated, filtered, archived, enriched, scored, written, packages);
        // After the work, never before it: a run that failed halfway must not leave a row
        // claiming a clean pass. The same placement rule the per-source row follows, and
        // the recorder cannot throw — a history row is worth less than the run.
        history.record(report, startedAt, scoringModel, stages.timings());
        return report;
    }

    /**
     * Compares the extraction against the count the document announces about itself, when
     * the source says how to read it. This is the one check nothing else can make: a
     * selector that stops matching loses offers, and fewer offers is indistinguishable
     * from a quiet day on the market. Loud, and not fatal — the offers that did come
     * through are still worth having.
     */
    private DocumentIngestResult check(Source source, RawDocument document, int extracted) {
        String pattern = source.extraction().expectCountFromSubject();
        if (pattern == null || document.subject() == null) {
            return new DocumentIngestResult(document.id(), extracted, null);
        }
        var matcher = java.util.regex.Pattern.compile(pattern).matcher(document.subject());
        if (!matcher.find() || matcher.groupCount() < 1) {
            log.warn(
                    "Source '{}': '{}' does not state a count, though the source expects one",
                    source.id(),
                    document.subject());
            return new DocumentIngestResult(document.id(), extracted, null);
        }
        int announced = Integer.parseInt(matcher.group(1));
        if (announced != extracted) {
            log.warn(
                    "Source '{}': {} announces {} offers, {} were extracted — the selectors have drifted",
                    source.id(),
                    document.id(),
                    announced,
                    extracted);
        }
        return new DocumentIngestResult(document.id(), extracted, announced);
    }

    /**
     * Which extractor reads a document is the source's decision, not this method's. Both
     * hand back the same shape — a block per offer, keyed by the eight field names — so
     * everything after this point is identical whether the offer came out of a newsletter
     * or out of a file somebody dropped in by hand.
     */
    private List<ExtractedOffer> read(Source source, RawDocument document) {
        List<Map<String, Object>> blocks =
                switch (source.extraction().strategy()) {
                    case HTML_BLOCKS -> extractor.extract(document.html(), source.extraction());
                    case MARKDOWN_FRONTMATTER -> markdown.extract(document.html(), source.extraction());
                    default -> List.of();
                };
        return blocks.stream()
                .map(block -> mapper.map(block, source.extraction(), document.receivedAt()))
                .filter(offer -> offer.title() != null && !offer.title().isBlank())
                .toList();
    }

    /**
     * What the documents of this run announced in total, or null when none of them says.
     * Summed rather than kept per document: the screen asks whether anything was lost,
     * and the per-document detail is in the report the run returns.
     */
    private static Integer announced(List<DocumentIngestResult> details) {
        var stated = details.stream()
                .map(DocumentIngestResult::announced)
                .filter(java.util.Objects::nonNull)
                .toList();
        return stated.isEmpty()
                ? null
                : stated.stream().mapToInt(Integer::intValue).sum();
    }

    private SourceIngestResult ingest(Source source, SourceConnector connector) {
        String strategy = source.extraction().strategy();
        if (!HTML_BLOCKS.equals(strategy) && !MARKDOWN_FRONTMATTER.equals(strategy)) {
            log.warn(
                    "Source '{}' asks for extraction strategy '{}', which is not implemented yet",
                    source.id(),
                    strategy);
            return new SourceIngestResult(source.id(), 0, 0, 0, List.of());
        }

        long sourceId = store.sourceId(source.id(), source.type());
        List<RawDocument> documents = connector.read(source, sourceId);
        List<DocumentIngestResult> details = new ArrayList<>();
        int extracted = 0;
        int written = 0;

        for (RawDocument document : documents) {
            List<ExtractedOffer> offers = read(source, document);

            details.add(check(source, document, offers.size()));
            extracted += offers.size();
            written += store.store(sourceId, offers);
        }

        // Only now, after everything is stored: a cursor advanced before the write would
        // skip those mails forever if the write failed, and nothing would say so.
        connector.commit(source, sourceId, documents);

        log.info(
                "Source '{}': {} documents, {} offers extracted, {} rows written",
                source.id(),
                documents.size(),
                extracted,
                written);
        // After the commit, so a run that failed halfway does not claim a clean pass.
        store.recordRun(sourceId, documents.size(), extracted, written, announced(details));
        return new SourceIngestResult(source.id(), documents.size(), extracted, written, details);
    }
}
