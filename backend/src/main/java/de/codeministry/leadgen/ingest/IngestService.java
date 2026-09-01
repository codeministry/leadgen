package de.codeministry.leadgen.ingest;

import de.codeministry.leadgen.config.ConfigRegistry;
import de.codeministry.leadgen.config.model.SourcesConfig.Source;
import de.codeministry.leadgen.dedupe.DeduplicationService;
import de.codeministry.leadgen.filter.FilterService;
import de.codeministry.leadgen.ingest.connector.SourceConnector;
import de.codeministry.leadgen.ingest.extract.HtmlBlockExtractor;
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

    private final ConfigRegistry config;
    private final Map<String, SourceConnector> connectors;
    private final HtmlBlockExtractor extractor;
    private final OfferMapper mapper;
    private final OfferStore store;
    private final DeduplicationService dedupe;
    private final FilterService filter;

    IngestService(
            ConfigRegistry config,
            List<SourceConnector> connectors,
            HtmlBlockExtractor extractor,
            OfferMapper mapper,
            OfferStore store,
            DeduplicationService dedupe,
            FilterService filter) {
        this.config = config;
        this.connectors = connectors.stream().collect(Collectors.toMap(SourceConnector::type, Function.identity()));
        this.extractor = extractor;
        this.mapper = mapper;
        this.store = store;
        this.dedupe = dedupe;
        this.filter = filter;
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
        // cluster judged once.
        return new IngestReport(results, dedupe.run(), filter.run());
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

    private SourceIngestResult ingest(Source source, SourceConnector connector) {
        if (!HTML_BLOCKS.equals(source.extraction().strategy())) {
            log.warn(
                    "Source '{}' asks for extraction strategy '{}', which is not implemented yet",
                    source.id(),
                    source.extraction().strategy());
            return new SourceIngestResult(source.id(), 0, 0, 0, List.of());
        }

        long sourceId = store.sourceId(source.id(), source.type());
        List<RawDocument> documents = connector.read(source, sourceId);
        List<DocumentIngestResult> details = new ArrayList<>();
        int extracted = 0;
        int written = 0;

        for (RawDocument document : documents) {
            List<ExtractedOffer> offers = extractor.extract(document.html(), source.extraction()).stream()
                    .map(block -> mapper.map(block, source.extraction()))
                    .filter(offer -> offer.title() != null && !offer.title().isBlank())
                    .toList();

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
