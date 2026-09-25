import {LastRunView} from '@core/model/last-run';
import {WorkflowStage, WorkflowView} from '@core/model/workflow';

/**
 * What the last run left at each stage of the workflow, keyed by stage id.
 *
 * One mapping, reading the fields the dashboard reads, so the two screens cannot disagree
 * (spec 008, plan § Risks). A stage the recorded run carries no number for is `null`: an
 * absent count is shown as none, never as a zero standing in for "not measured".
 *
 * Only the stages a run figure genuinely exists for carry one (spec 008 § Decisions,
 * "ISC-287 names the stages that carry a count"): an ingest source's extracted, FILTER's
 * removals, ENRICH's enriched, SCORE's scored and PACKAGE's packaged. Every other stage is
 * `null`, including DEDUPE, OPEN and ARCHIVE, whose `merged`, `shortlisted` and `archived`
 * figures are standing totals rather than what this run itself did, and would read as a run
 * count beside a stage name without being one.
 *
 * Without a last run the record is empty; the words for that case are the rail's, not this
 * function's.
 */
export function stageCounts(
    workflow: WorkflowView | null,
    lastRun: LastRunView | null,
): Record<string, number | null> {
    if (workflow === null || lastRun === null) {
        return {};
    }
    const counts: Record<string, number | null> = {};
    for (const phase of workflow.phases) {
        for (const stage of phase.stages) {
            counts[stage.id] = countOf(stage, lastRun);
        }
    }
    return counts;
}

function countOf(stage: WorkflowStage, run: LastRunView): number | null {
    if (stage.kind === 'ingest') {
        return run.sources.find((source) => source.sourceId === stage.sourceId)?.extracted ?? null;
    }
    // A failed or batched run keeps its counters at zero for the stages it never finished, and a
    // zero there would read as a measurement. A run recorded before stage timing existed has no
    // rows at all, and keeps the old reading.
    if (run.stages.length > 0 && !run.stages.some((row) => row.stage === stage.id && row.status === 'OK')) {
        return null;
    }
    switch (stage.id) {
        case 'FILTER':
            // The sum over `removed`, the figure the dashboard's run summary states as removed.
            return Object.values(run.removed).reduce((sum, count) => sum + count, 0);
        case 'ENRICH':
            return run.enriched;
        case 'SCORE':
            return run.scored;
        case 'PACKAGE':
            return run.packaged;
        default:
            // DEDUPE, OPEN, ARCHIVE: `merged`, `shortlisted` and `archived` are standing
            // totals, not run figures (spec 008 § Decisions).
            // CONTENT, FIELDS, RETRIEVAL: the recorded run carries no number.
            // DIGEST: `digestWritten` is a yes or no, not a count.
            return null;
    }
}

/**
 * The catalog key of the words a count chip puts around its number ("8 read", "−12,548 held
 * back"), or `null` for a stage that carries no run count. Keyed exactly as `countOf` above, so
 * a stage cannot gain a number without gaining the words for it.
 */
export function countVerbKey(stage: WorkflowStage): string | null {
    if (stage.kind === 'ingest') {
        return 'rules.count.read';
    }
    switch (stage.id) {
        case 'FILTER':
        case 'ENRICH':
        case 'SCORE':
        case 'PACKAGE':
            return `rules.count.${stage.id.toLowerCase()}`;
        default:
            return null;
    }
}

/** U+2212, the typographic minus: a hyphen reads as a dash beside a grouped number. */
const MINUS = '−';

/**
 * A stage's run count grouped the way the given language groups it (en "12,548", de "12.548").
 * FILTER's figure is what it removed, so it carries a leading minus sign — unless it removed
 * nothing, where "−0" would claim a removal that did not happen.
 */
export function formatStageCount(stage: WorkflowStage, count: number, lang: string): string {
    const grouped = new Intl.NumberFormat(lang).format(count);
    return stage.id === 'FILTER' && count > 0 ? `${MINUS}${grouped}` : grouped;
}
