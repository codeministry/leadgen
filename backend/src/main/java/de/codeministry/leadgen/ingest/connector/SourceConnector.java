package de.codeministry.leadgen.ingest.connector;

import de.codeministry.leadgen.config.model.SourcesConfig.Source;
import de.codeministry.leadgen.ingest.RawDocument;
import java.util.List;

/** Fetches documents for one kind of source. One implementation per `type` in `sources.yaml`. */
public interface SourceConnector {

    /** The `type` value this connector answers to. */
    String type();

    List<RawDocument> read(Source source, long sourceId);

    /**
     * Marks the documents as done, after they have been extracted and stored.
     *
     * <p>Separate from {@link #read} on purpose. A cursor advanced at read time and a
     * failure afterwards means those mails are never looked at again, and nothing says so
     * — the archive is simply missing a day. Committing after the write costs at most a
     * repeated extraction, which the upsert absorbs.
     */
    default void commit(Source source, long sourceId, List<RawDocument> processed) {
        // Sources without progress to track, such as a file drop, re-read everything.
    }
}
