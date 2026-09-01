package de.codeministry.leadgen.ingest;

import java.util.List;

/**
 * What one ingest run did, per source and per document.
 *
 * @param merged how many offers are attached to a primary after the run, inside the
 *     deduplication window. The standing total, not the rows this run moved: a second run
 *     moves nothing, and a zero there would read as "deduplication stopped working".
 */
public record IngestReport(List<SourceIngestResult> sources, int merged) {

    public int extracted() {
        return sources.stream().mapToInt(SourceIngestResult::extracted).sum();
    }

    public int written() {
        return sources.stream().mapToInt(SourceIngestResult::written).sum();
    }
}
