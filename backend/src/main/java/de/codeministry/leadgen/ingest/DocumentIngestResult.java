package de.codeministry.leadgen.ingest;

/**
 * One document's contribution to a run.
 *
 * @param announced what the document said it contained, when it says so at all — the
 *     newsletter names the number in its subject line. Null when the source declares no
 *     `expect_count_from_subject`.
 */
public record DocumentIngestResult(String documentId, int extracted, Integer announced) {

    /** False only when the document stated a count and the extraction missed it. */
    public boolean complete() {
        return announced == null || announced == extracted;
    }
}
