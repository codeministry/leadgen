package de.codeministry.leadgen.ingest;

import java.time.Instant;

/**
 * One fetched document, before anything is known about its contents. A newsletter
 * mail, a portal page, a dropped file — the connector's job ends here.
 *
 * @param id stable within the source: a file name now, an IMAP UID next
 */
public record RawDocument(String id, String subject, String html, Instant receivedAt) {}
