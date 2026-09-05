/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 Marcello Muscara (codeministry)
 *
 * Licensed under the Apache License, Version 2.0. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0
 */
package de.codeministry.leadgen.ingest;

import de.codeministry.leadgen.archive.ArchiveReport;
import de.codeministry.leadgen.enrich.EnrichmentReport;
import de.codeministry.leadgen.filter.FilterReport;
import de.codeministry.leadgen.packaging.PackageReport;
import de.codeministry.leadgen.score.ScoringReport;
import java.nio.file.Path;
import java.time.Instant;
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
 * @param finishedAt when the run ended. Present for the same reason {@code LastRunView}
 *     carries it: without a time on the panel, a pass that ran for three hours and one
 *     that was clicked a minute ago read identically. The start is deliberately not here
 *     — the history row keeps it, and a duration nobody asked for is a second number to
 *     explain on a screen that answers "what came in this morning".
 */
public record IngestReport(
        List<SourceIngestResult> sources,
        int merged,
        FilterReport filtered,
        ArchiveReport archived,
        EnrichmentReport enriched,
        ScoringReport scored,
        Path digest,
        PackageReport packaged,
        Instant finishedAt) {

    public int extracted() {
        return sources.stream().mapToInt(SourceIngestResult::extracted).sum();
    }

    public int written() {
        return sources.stream().mapToInt(SourceIngestResult::written).sum();
    }
}
