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
