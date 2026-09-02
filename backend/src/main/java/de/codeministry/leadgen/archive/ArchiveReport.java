package de.codeministry.leadgen.archive;

/**
 * What the age pass did, and what the archive looks like afterwards.
 *
 * @param archived rows this run took off the working list.
 * @param restored rows this run put back, because the window was widened. Only rows the
 *     age pass had archived itself are eligible; a manual decision is never undone by a
 *     rule.
 * @param standing the whole archive, not what this run moved. The same rule
 *     {@code IngestReport.merged} follows: a second run legitimately moves nothing, and a
 *     zero there would read as "archiving stopped working".
 * @param undated offers with no publication date, which no age rule can judge. They stay
 *     on the working list forever, and that is worth a number rather than a silence.
 */
public record ArchiveReport(int archived, int restored, int standing, int undated) {}
