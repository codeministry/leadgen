package de.codeministry.leadgen.ingest;

import java.util.List;

/** What one ingest run did, per source and per document. */
public record IngestReport(List<SourceIngestResult> sources) {

    public int extracted() {
        return sources.stream().mapToInt(SourceIngestResult::extracted).sum();
    }

    public int written() {
        return sources.stream().mapToInt(SourceIngestResult::written).sum();
    }
}
