package de.codeministry.leadgen.ingest.connector;

import de.codeministry.leadgen.config.model.SourcesConfig.Source;
import de.codeministry.leadgen.ingest.RawDocument;
import java.util.List;

/** Fetches documents for one kind of source. One implementation per `type` in `sources.yaml`. */
public interface SourceConnector {

    /** The `type` value this connector answers to. */
    String type();

    List<RawDocument> read(Source source);
}
