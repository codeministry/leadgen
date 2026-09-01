package de.codeministry.leadgen.ingest;

import de.codeministry.leadgen.config.ConfigRegistry;
import de.codeministry.leadgen.config.model.SourcesConfig.Source;
import de.codeministry.leadgen.dedupe.DeduplicationService;
import de.codeministry.leadgen.enrich.EnrichmentService;
import de.codeministry.leadgen.filter.FilterService;
import de.codeministry.leadgen.digest.DigestService;
import de.codeministry.leadgen.packaging.PackagingService;
import de.codeministry.leadgen.score.ScoringService;
import de.codeministry.leadgen.ingest.connector.SourceConnector;
import de.codeministry.leadgen.ingest.extract.HtmlBlockExtractor;
import de.codeministry.leadgen.ingest.extract.MarkdownExtractor;
import de.codeministry.leadgen.ingest.extract.OfferMapper;
import de.codeministry.leadgen.ingest.store.OfferStore;
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
    private final EnrichmentService enrich;
    private final ScoringService scoring;
    private final PackagingService packaging;
    private final DigestService digest;

    IngestService(
            ConfigRegistry config,
            List<SourceConnector> connectors,
            HtmlBlockExtractor extractor,
            MarkdownExtractor markdown,
            OfferMapper mapper,
            OfferStore store,
            DeduplicationService dedupe,
            FilterService filter,
            EnrichmentService enrich,
            ScoringService scoring,
            PackagingService packaging,
            DigestService digest) {
        this.config = config;
        this.connectors = connectors.stream().collect(Collectors.toMap(SourceConnector::type, Function.identity()));
        this.extractor = extractor;
        this.markdown = markdown;
        this.mapper = mapper;
        this.store = store;
        this.dedupe = dedupe;
        this.filter = filter;
        this.enrich = enrich;
        this.scoring = scoring;
        this.packaging = packaging;
        this.digest = digest;
    }

    public IngestReport run() {
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
                results.add(ingest(source, connector));
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
        var deduplicated = dedupe.run();
        var filtered = filter.run();
        var enriched = enrich.run();
        var scored = scoring.run();
        // Packaging before the digest, so the digest can say which offers already have a
        // folder. Both write files and neither sends anything.
        var packages = packaging.run();
        var written = digest.render(java.time.LocalDate.now()).orElse(null);
        return new IngestReport(results, deduplicated, filtered, enriched, scored, written, packages);
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
            log.warn("Source '{}': '{}' does not state a count, though the source expects one",
                    source.id(), document.subject());
            return new DocumentIngestResult(document.id(), extracted, null);
        }
        int announced = Integer.parseInt(matcher.group(1));
        if (announced != extracted) {
            log.warn("Source '{}': {} announces {} offers, {} were extracted — the selectors have drifted",
                    source.id(), document.id(), announced, extracted);
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
        List<Map<String, Object>> blocks = switch (source.extraction().strategy()) {
            case HTML_BLOCKS -> extractor.extract(document.html(), source.extraction());
            case MARKDOWN_FRONTMATTER -> markdown.extract(document.html(), source.extraction());
            default -> List.of();
        };
        return blocks.stream()
                .map(block -> mapper.map(block, source.extraction()))
                .filter(offer -> offer.title() != null && !offer.title().isBlank())
                .toList();
    }

    private SourceIngestResult ingest(Source source, SourceConnector connector) {
        String strategy = source.extraction().strategy();
        if (!HTML_BLOCKS.equals(strategy) && !MARKDOWN_FRONTMATTER.equals(strategy)) {
            log.warn("Source '{}' asks for extraction strategy '{}', which is not implemented yet",
                    source.id(), strategy);
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

        log.info("Source '{}': {} documents, {} offers extracted, {} rows written",
                source.id(), documents.size(), extracted, written);
        return new SourceIngestResult(source.id(), documents.size(), extracted, written, details);
    }
}
