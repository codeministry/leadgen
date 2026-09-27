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
import de.codeministry.leadgen.analytics.StageTiming;
import de.codeministry.leadgen.application.ApplicationService;
import de.codeministry.leadgen.application.OpenReport;
import de.codeministry.leadgen.archive.ArchiveReport;
import de.codeministry.leadgen.archive.ArchiveService;
import de.codeministry.leadgen.config.ConfigRegistry;
import de.codeministry.leadgen.config.model.SourcesConfig.Source;
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
import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
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
@RequiredArgsConstructor
public class IngestService {

    private static final String HTML_BLOCKS = "html-blocks";
    /**
     * One document is one offer, and the frontmatter carries the eight fields.
     */
    private static final String MARKDOWN_FRONTMATTER = "markdown-frontmatter";
    /**
     * One document is one offer here too, and nothing about it is addressable: prose a
     * person wrote. See {@link LlmDocumentExtractor} for why this is not a fallback.
     */
    private static final String LLM = "llm";

    /**
     * The strategies this class dispatches on, named once.
     *
     * <p>Public because the test that matters is not "does the switch have three arms" but
     * "does every strategy the shipped `sources.yaml` names have one". A value spelled
     * differently in the configuration than in the code is extracted by nobody and logged
     * as unimplemented, which reads like a deliberate gap rather than the typo it is.
     */
    public static final java.util.Set<String> IMPLEMENTED_STRATEGIES =
            java.util.Set.of(HTML_BLOCKS, MARKDOWN_FRONTMATTER, LLM);

    private final ConfigRegistry config;
    private final List<SourceConnector> connectors;
    private final HtmlBlockExtractor extractor;
    private final MarkdownExtractor markdown;
    private final LlmDocumentExtractor prose;
    private final OfferMapper mapper;
    private final OfferStore store;
    private final DeduplicationService dedupe;
    private final FilterService filter;
    private final ArchiveService archive;
    private final EnrichmentService enrich;
    private final ContentService content;
    private final FieldsService fields;
    private final ScoringService scoring;
    private final RetrievalIndexService retrieval;
    private final ApplicationService applications;
    private final PackagingService packaging;
    private final DigestService digest;
    private final PipelineRunRecorder history;

    /**
     * The connector answering to a source's `type`, looked up in the injected list rather than
     * a map built in a constructor: there are a handful of connectors and one lookup per source.
     */
    private Optional<SourceConnector> connector(String type) {
        return connectors.stream().filter(c -> c.type().equals(type)).findFirst();
    }

    /**
     * Two connectors answering to one `type` used to fail the context when the constructor
     * built its map; a lookup over the list would let the first one win in silence. Same
     * refusal, at the same moment, from the container's own lifecycle instead of the
     * constructor.
     */
    @PostConstruct
    void refuseDuplicateConnectorTypes() {
        var seen = new java.util.HashSet<String>();
        for (SourceConnector connector : connectors) {
            if (!seen.add(connector.type())) {
                throw new IllegalStateException("Two source connectors answer to type '" + connector.type() + "'");
            }
        }
    }

    /**
     * One pass with the configured default scoring model.
     */
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
    /**
     * Thrown when a pass is already running. Not a queue: a second run is not work waiting
     * its turn, it is the same work done twice.
     */
    public static class AlreadyRunning extends RuntimeException {
        public AlreadyRunning() {
            super("an ingest run is already in progress; this one was not started");
        }
    }

    /**
     * Thrown when a stage threw. The cause is the stage's own exception, untouched; the
     * message names the stage, because that is the one thing the reader has to act on and
     * the one thing the cause cannot say about itself. Raised after the history row has been
     * closed as FAILED, so the request fails loudly and the row still says where.
     */
    public static class StageFailed extends RuntimeException {
        private final String stage;

        public StageFailed(String stage, RuntimeException cause) {
            super("the ingest run failed in stage " + stage + ": " + cause.getMessage(), cause);
            this.stage = stage;
        }

        public String stage() {
            return stage;
        }
    }

    /**
     * One pass at a time, and this is not caution.
     *
     * <p>Every stage rewrites the same table: the filter alone writes a verdict on all
     * 13,716 rows with no {@code WHERE}, because the rules are hot-reloadable and a partial
     * re-judge would split the archive across two rule sets. Two passes therefore contend
     * for the same rows, and the loser waits — measured on the cluster, thirteen minutes
     * behind a scoring transaction, with a third run stacked behind that.
     *
     * <p>The CronJob's {@code concurrencyPolicy: Forbid} covers only the jobs the CronJob
     * itself creates. It says nothing about the button, and the button is how a run is
     * normally started.
     */
    private final java.util.concurrent.locks.ReentrantLock pass = new java.util.concurrent.locks.ReentrantLock();

    public IngestReport run(String scoringModel) {
        // tryLock, never lock: a caller that waits is a request held open for hours, and
        // the answer it eventually gets is a report about somebody else's run.
        if (!pass.tryLock()) {
            throw new AlreadyRunning();
        }
        try {
            return runOnce(scoringModel);
        } finally {
            pass.unlock();
        }
    }

    /**
     * The stages every run has, whatever its sources are: dedupe, filter, archive, enrich,
     * content, fields, score, retrieval, open, package, digest — in the order
     * {@code IngestOrderTest} pins and named exactly as the {@code stages.time(...)} calls
     * below spell them.
     *
     * <p>Public because the workflow view is held against it: it lives in a different
     * package and has no other way to know what a run actually times without duplicating
     * this list and drifting from it. The order here is not read by anything — the sequence
     * below is still written out rather than driven off this list — but a name absent from
     * it is what {@code WorkflowController}'s test fails on.
     */
    public static final List<String> GLOBAL_STAGE_NAMES = List.of(
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

    /**
     * A constant because the sequence below is written out rather than driven by a list,
     * and it is pinned by {@code IngestOrderTest}, which verifies both the order and that
     * there are this many of them. Without that test it is the kind of number that is wrong
     * for a month before anybody notices the progress bar stopping at eight of nine.
     */
    static final int GLOBAL_STAGES = GLOBAL_STAGE_NAMES.size();

    private IngestReport runOnce(String scoringModel) {
        // Before anything else, because scoring is the last stage: checked only there, a
        // name nobody configured is refused after the sources have been read, the
        // duplicates clustered, the filter applied and the surviving ads fetched from
        // their portals — a whole pass spent to answer that a model is unknown.
        scoring.checkModel(scoringModel);
        // Captured before anything runs: the row this ends in states how long the run
        // took, and `now()` at the end would state only when it stopped.
        var startedAt = java.time.Instant.now();
        // The row is opened here rather than written at the end, and the intent behind the
        // old placement survives: it says RUNNING and carries zeros, so it claims nothing.
        // What it buys is that a run in flight is visible to the next one — without it the
        // dashboard's source window has no upper bound and lists every source twice.
        // Decided before the row is opened, because the row states it: one stage per source
        // that will actually run plus the fixed ones. Resolved into a list first rather than
        // skipped inside the loop, so "which sources count" is decided once — counted one way
        // and iterated another, the progress would say 6 of 8 and then stop at 7.
        var runnable = config.snapshot().sources().sources().stream()
                .filter(Source::enabled)
                .filter(source -> {
                    if (connector(source.type()).isPresent()) {
                        return true;
                    }
                    // Not fatal: a config may declare a source type a later step implements.
                    log.warn(
                            "Source '{}' has type '{}', for which no connector exists yet", source.id(), source.type());
                    return false;
                })
                .toList();
        var runId = history.start(startedAt, scoringModel, runnable.size() + GLOBAL_STAGES);
        // Where the time went, collected as the run goes and written with the history row at
        // the end. A run whose counts look ordinary can still have spent four minutes in
        // enrichment because one portal was slow, and nothing in the counts says so.
        //
        // The marker is the other half and a different question: not where the time went, but
        // where the run is right now. It overwrites the open row, so it is worth something
        // only while the run is going — which is exactly when the per-stage table has nothing
        // to say, because that one is written after the work.
        var stages = new StageLog(
                runId.isPresent()
                        ? (position, stage) -> history.mark(runId.getAsLong(), position, stage)
                        : StageLog.Marker.NONE);
        List<SourceIngestResult> results = new ArrayList<>();
        // Declared ahead of the work and at "nothing happened", so a stage that throws still
        // leaves a report to write: the counts up to that stage, and for the stages it never
        // reached the same value each of them answers when it is switched off. That is what
        // lets the history row say where a run stopped instead of saying RUNNING forever.
        int deduplicated = 0;
        FilterReport filtered = FilterReport.nothing();
        ArchiveReport archived = ArchiveReport.nothing();
        EnrichmentReport enriched = EnrichmentReport.skipped();
        ContentReport segmented = ContentReport.skipped();
        FieldsReport extractedFields = FieldsReport.skipped();
        ScoringReport scored = ScoringReport.nothing();
        RetrievalReport indexed = RetrievalReport.skipped();
        OpenReport opened = OpenReport.nothing();
        PackageReport packages = PackageReport.nothing();
        java.nio.file.Path written = null;
        RuntimeException failure = null;

        try {
            for (Source source : runnable) {
                SourceConnector connector = connector(source.type()).orElseThrow();
                try {
                    // Timed per source rather than as one block: "ingest took four minutes" is
                    // not actionable, "the mailbox took four minutes and the two file sources
                    // took nothing" is.
                    results.add(stages.time("INGEST " + source.id(), () -> ingest(source, connector)));
                } catch (IngestException e) {
                    // One unreachable mailbox must not stop the file sources behind it. The
                    // timing stays FAILED under a run that goes on to COMPLETE, which is why
                    // the run's own status is never read off the timings.
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
            deduplicated = stages.time("DEDUPE", dedupe::run, attached -> widthNote(dedupe.lastWidth()));
            filtered = stages.time("FILTER", filter::run);
            // After the filter, so an offer somebody restores carries a current verdict; before
            // enrichment, because that is the stage that leaves the machine and scoring is the
            // one that costs money. An offer that has aged off the working list must pay for
            // neither.
            archived = stages.time("ARCHIVE", archive::run);
            enriched = stages.time("ENRICH", enrich::run, report -> widthNote(report.width()));
            // Between the two on purpose. After enrichment because it reads `full_text`, and
            // before scoring because scoring has to judge the advert rather than the portal's
            // furniture around it — a tag cloud of sixty technology names the client never asked
            // for otherwise counts as skill overlap.
            segmented = stages.time("CONTENT", content::run, report -> widthNote(report.width()));
            // After content because it reads the advert the content stage left, not the page the
            // portal wrapped it in — a deadline found in a footer is the same class of error as a
            // tag cloud counted as skill overlap. Before scoring because what it writes feeds
            // `project_setup` and the judge's description of an offer, and because it nulls
            // `score_model` on the offers whose values actually moved.
            extractedFields = stages.time("FIELDS", fields::run, report -> widthNote(report.width()));
            scored = stages.time("SCORE", () -> scoring.run(scoringModel), report -> widthNote(report.width()));
            // After scoring and not after content, where its input is ready — and the order is the
            // whole argument, so it is written here rather than left to be rediscovered.
            // `LlmBudget` is one allowance shared by every stage, and the first pass after this is
            // switched on walks the whole working list: a few thousand offers is over a hundred
            // requests at a batch of thirty-two. In front of SCORE that backfill spends the day and
            // the shortlist goes unjudged, which is a new and unproven stage starving the one the
            // tool exists for. Behind it, the same backfill degrades only the semantic search, and
            // the search has a deterministic fallback. Nothing between CONTENT and SCORE reads the
            // column, so waiting costs nothing.
            indexed = stages.time("RETRIEVAL", retrieval::run, report -> widthNote(report.width()));
            // What the run owes a person: a card for everything it decided to recommend. It used
            // to build the folder here as well, for all of them, which is how the deployed
            // instance came to hold 93 packages against 2 applications ever sent. The folder now
            // waits for somebody to agree, and this stage costs one statement.
            opened = stages.time("OPEN", applications::openShortlisted);
            // And the retry for anything whose folder was asked for and not built — normally
            // nothing. Before the digest because both write files, and neither sends anything.
            packages = stages.time("PACKAGE", packaging::run);
            written = stages.time(
                    "DIGEST", () -> digest.render(java.time.LocalDate.now()).orElse(null));
        } catch (RuntimeException e) {
            // A stage threw. `StageLog` has recorded it as FAILED and rethrown; what is left is
            // to close the row with what the run had counted up to here, so the history says
            // where it stopped. Kept rather than handled here, so the report below is assembled
            // once for both endings instead of twice with the same thirteen components.
            failure = e;
        }
        // `now()` here and not `startedAt`: the panel answers "when did this finish", the
        // history row answers "how long did it take", and they are different questions.
        var report = IngestReport.builder()
                .sources(results)
                .merged(deduplicated)
                .filtered(filtered)
                .archived(archived)
                .enriched(enriched)
                .segmented(segmented)
                .fields(extractedFields)
                .scored(scored)
                .indexed(indexed)
                .digest(written)
                .opened(opened)
                .packaged(packages)
                .finishedAt(java.time.Instant.now())
                .build();
        if (failure != null) {
            // The stage is the last timing, because `StageLog.time` appends before it rethrows
            // and nothing runs after a throw. Logged here with the trace: the controller answers
            // this with a sentence, and then Boot logs nothing itself.
            var timings = stages.timings();
            String stage = timings.isEmpty()
                            || !StageTiming.FAILED.equals(timings.getLast().status())
                    ? "?"
                    : timings.getLast().stage();
            log.error("The run failed in stage {}: {}", stage, failure.getMessage(), failure);
            history.recordFailure(runId, report, startedAt, scoringModel, timings);
            throw new StageFailed(stage, failure);
        }
        // After the work, never before it: a run that failed halfway must not leave a row
        // claiming a clean pass — it leaves one saying FAILED instead, from the branch above.
        // The same placement rule the per-source row follows, and the recorder cannot throw:
        // a history row is worth less than the run.
        history.record(runId, report, startedAt, scoringModel, stages.timings());
        return report;
    }

    /**
     * The note on a model-bound stage's {@code pipeline_stage} row: {@code width=N} when it ran
     * above width 1, and nothing at width 1, so a sequential run writes the rows it always has.
     * The width comes from the stage's own report — the one its log line named — so a stage that
     * was skipped, had nothing due, had no model to ask or handed its work to a batch reads as
     * 1 here exactly as it does there.
     */
    static String widthNote(int width) {
        return width > 1 ? "width=" + width : null;
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
                    case LLM -> prose.extract(document.html(), source.extraction());
                    // Unreachable while the guard and this switch agree, which is the point
                    // of saying it out loud: a strategy the guard lets through and no branch
                    // reads would otherwise be a source that extracts nothing, in silence.
                    default -> {
                        log.error(
                                "Source '{}' passed the strategy guard with '{}' and no branch read it;"
                                        + " IMPLEMENTED_STRATEGIES and the dispatch disagree",
                                source.id(),
                                source.extraction().strategy());
                        yield List.of();
                    }
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
        if (!IMPLEMENTED_STRATEGIES.contains(strategy)) {
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
