package de.codeministry.leadgen.ingest.store;

/**
 * How far a mailbox folder has been read.
 *
 * @param uidValidity the folder's identity. When the server changes it, every UID it ever
 *     handed out is void — the folder was recreated, and a cursor kept across that would
 *     silently skip its whole contents.
 * @param lastUid the highest UID processed. Zero means nothing yet.
 */
public record IngestCursor(long uidValidity, long lastUid) {

    public static final IngestCursor NONE = new IngestCursor(0, 0);

    public IngestCursor validFor(long currentUidValidity) {
        return uidValidity == currentUidValidity ? this : new IngestCursor(currentUidValidity, 0);
    }
}
