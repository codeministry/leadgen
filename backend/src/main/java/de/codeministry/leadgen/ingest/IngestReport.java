package de.codeministry.leadgen.ingest;

import java.util.List;

/**
 * What one ingest run did. Counted per document as well as per source, because the
 * per-document count is the only thing that can be checked against something the source
 * states itself: the newsletter announces the number of offers in its subject line.
 */
public record IngestReport(List<SourceResult> sources) {

    /**
     * @param written rows the upsert touched, insert or update alike — not new rows.
     *     Re-reading the same mail writes every offer again and adds none.
     */
    public record SourceResult(
            String sourceId, int documents, int extracted, int written, List<DocumentResult> details) {}

    public record DocumentResult(String documentId, int extracted) {}

    public int extracted() {
        return sources.stream().mapToInt(SourceResult::extracted).sum();
    }

    public int written() {
        return sources.stream().mapToInt(SourceResult::written).sum();
    }
}
