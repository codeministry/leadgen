package de.codeministry.leadgen.ingest;

/** A source could not be read. One failing source must not end the whole run. */
public class IngestException extends RuntimeException {

    public IngestException(String message, Throwable cause) {
        super(message, cause);
    }

    public IngestException(String message) {
        super(message);
    }
}
