package de.codeministry.leadgen.ingest;

import de.codeministry.leadgen.archive.ArchiveReport;
import de.codeministry.leadgen.enrich.EnrichmentReport;
import de.codeministry.leadgen.filter.FilterReport;
import de.codeministry.leadgen.packaging.PackageReport;
import de.codeministry.leadgen.score.ScoringReport;
import java.nio.file.Path;
import java.util.List;

/**
 * What one ingest run did, per source and per document.
 *
 * @param merged how many offers are attached to a primary after the run, inside the
 *     deduplication window. The standing total, not the rows this run moved: a second run
 *     moves nothing, and a zero there would read as "deduplication stopped working".
 * @param filtered what the hard filter did, per stage. The share that survives is the
 *     daily language-model budget, so this is the number the whole economics rests on.
 * @param archived what aged off the working list, and what came back onto it. Runs
 *     between the filter and enrichment, so nothing archived is ever paid for.
 * @param enriched what fetching the original ads did. The only stage that leaves the
 *     machine, and the only one that can fail for reasons unrelated to the offer.
 * @param scored what the shortlist looks like afterwards. `unscored` above zero means no
 *     language model was configured; the offers are there, only unranked.
 * @param digest the file the run wrote, or null when the digest is switched off. A file,
 *     never a message: the tool has no send path at all.
 * @param packaged the folders built for everything above the shortlist threshold. Folders
 *     on disk, for the same reason.
 */
public record IngestReport(
        List<SourceIngestResult> sources,
        int merged,
        FilterReport filtered,
        ArchiveReport archived,
        EnrichmentReport enriched,
        ScoringReport scored,
        Path digest,
        PackageReport packaged) {

    public int extracted() {
        return sources.stream().mapToInt(SourceIngestResult::extracted).sum();
    }

    public int written() {
        return sources.stream().mapToInt(SourceIngestResult::written).sum();
    }
}
