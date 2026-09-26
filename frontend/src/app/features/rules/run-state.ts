import {CurrentRunView} from '@core/model/current-run';
import {WorkflowView} from '@core/model/workflow';

/**
 * The `?stage=` value that opens the run status instead of a stage (operator, 2026-09-26). A
 * sentinel beside `UNREAD_STAGE` rather than a second query parameter: one screen, one sheet, one
 * thing in the URL that says what is open, and a link to the status is as shareable as a link to a
 * stage. No stage the server names can collide with it — the names are `DEDUPE`, `FILTER`,
 * `INGEST <source>` and the rest, all upper case and none of them a bare verb.
 */
export const RUN_STATUS = 'run';

/** Where one stage node stands relative to the run in flight. */
export type StageRunState = 'running' | 'done' | 'pending';

/**
 * The workflow's run order plus, when a pass is in flight, which stage carries it.
 *
 * `states` is keyed by `WorkflowStage.id` and empty whenever `current` is null — there is no
 * pass to place a node against, so no node carries any of the three states.
 */
export interface RunState {
    /** Every stage id the canvas draws, in the order a run executes them. */
    readonly order: readonly string[];
    /** The stage currently in flight, or null when no pass is reported. */
    readonly running: string | null;
    /** One state per stage id; empty without a pass. */
    readonly states: ReadonlyMap<string, StageRunState>;
}

/**
 * Joins a run onto the workflow's stages by `Stage.id`, never by `stagePosition`.
 *
 * `WorkflowView.Stage.id` is by construction the name the run times the stage under
 * (`"INGEST " + sourceId` for a source, the eleven fixed names otherwise), so matching the
 * reported stage against it is exact. Matching by position is not: `WorkflowService` draws one
 * node per *enabled* source, while `IngestService` walks the enabled sources *a connector exists
 * for* and computes `stageTotal` from those — a configured-but-unimplemented source type shifts
 * every reported position by one and would mark a stage done that never ran. `runState` therefore
 * reads `current.stage` and nothing numeric off `current`.
 */
export function runState(workflow: WorkflowView, current: CurrentRunView | null): RunState {
    const order = workflow.phases.flatMap((phase) => phase.stages.map((stage) => stage.id));
    const reportedStage = current?.stage ?? null;
    const runningIndex = reportedStage === null ? -1 : order.indexOf(reportedStage);
    const running = runningIndex === -1 ? null : reportedStage;

    const states = new Map<string, StageRunState>();
    if (running !== null) {
        order.forEach((id, index) => {
            const state: StageRunState = index < runningIndex ? 'done' : index === runningIndex ? 'running' : 'pending';
            states.set(id, state);
        });
    }

    return {order, running, states};
}
