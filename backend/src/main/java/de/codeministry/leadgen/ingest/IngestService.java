package de.codeministry.leadgen.ingest;

import de.codeministry.leadgen.config.ConfigRegistry;
import de.codeministry.leadgen.config.model.SourcesConfig.Source;
import de.codeministry.leadgen.ingest.connector.SourceConnector;
import de.codeministry.leadgen.ingest.extract.HtmlBlockExtractor;
import de.codeministry.leadgen.ingest.extract.OfferMapper;
import de.codeministry.leadgen.ingest.store.OfferStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
@Service
public class IngestService {

    private static final Logger log = LoggerFactory.getLogger(IngestService.class);
    private static final String HTML_BLOCKS = "html-blocks";

    private final ConfigRegistry config;
    private final Map<String, SourceConnector> connectors;
    private final HtmlBlockExtractor extractor;
    private final OfferMapper mapper;
    private final OfferStore store;

    IngestService(
            ConfigRegistry config,
            List<SourceConnector> connectors,
            HtmlBlockExtractor extractor,
            OfferMapper mapper,
            OfferStore store) {
        this.config = config;
        this.connectors = connectors.stream().collect(Collectors.toMap(SourceConnector::type, Function.identity()));
        this.extractor = extractor;
        this.mapper = mapper;
        this.store = store;
    }

    public IngestReport run() {
        List<IngestReport.SourceResult> results = new ArrayList<>();

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
            results.add(ingest(source, connector));
        }
        return new IngestReport(results);
    }

    private IngestReport.SourceResult ingest(Source source, SourceConnector connector) {
        if (!HTML_BLOCKS.equals(source.extraction().strategy())) {
            log.warn(
                    "Source '{}' asks for extraction strategy '{}', which is not implemented yet",
                    source.id(),
                    source.extraction().strategy());
            return new IngestReport.SourceResult(source.id(), 0, 0, 0, List.of());
        }

        long sourceId = store.sourceId(source.id(), source.type());
        List<RawDocument> documents = connector.read(source);
        List<IngestReport.DocumentResult> details = new ArrayList<>();
        int extracted = 0;
        int written = 0;

        for (RawDocument document : documents) {
            List<ExtractedOffer> offers = extractor.extract(document.html(), source.extraction()).stream()
                    .map(block -> mapper.map(block, source.extraction()))
                    .filter(offer -> offer.title() != null && !offer.title().isBlank())
                    .toList();

            details.add(new IngestReport.DocumentResult(document.id(), offers.size()));
            extracted += offers.size();
            written += store.store(sourceId, offers);
        }

        log.info("Source '{}': {} documents, {} offers extracted, {} rows written",
                source.id(), documents.size(), extracted, written);
        return new IngestReport.SourceResult(source.id(), documents.size(), extracted, written, details);
    }
}
