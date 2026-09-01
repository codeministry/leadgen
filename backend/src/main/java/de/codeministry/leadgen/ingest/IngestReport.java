package de.codeministry.leadgen.ingest;

import de.codeministry.leadgen.enrich.EnrichmentReport;
import de.codeministry.leadgen.filter.FilterReport;
import java.util.List;

/**
 * What one ingest run did, per source and per document.
 *
 * @param merged how many offers are attached to a primary after the run, inside the
 *     deduplication window. The standing total, not the rows this run moved: a second run
 *     moves nothing, and a zero there would read as "deduplication stopped working".
 * @param filtered what the hard filter did, per stage. The share that survives is the
 *     daily language-model budget, so this is the number the whole economics rests on.
 * @param enriched what fetching the original ads did. The only stage that leaves the
 *     machine, and the only one that can fail for reasons unrelated to the offer.
 */
public record IngestReport(
        List<SourceIngestResult> sources,
        int merged,
        FilterReport filtered,
        EnrichmentReport enriched) {

    public int extracted() {
        return sources.stream().mapToInt(SourceIngestResult::extracted).sum();
    }

    public int written() {
        return sources.stream().mapToInt(SourceIngestResult::written).sum();
    }
}
