import {WorkflowView} from '@core/model/workflow';
import {stageLabelKey} from './stage-marks';

/** How many sources run side by side, and the catalog key of the stage they merge into. */
export interface FanIn {
    readonly count: number;
    readonly targetKey: string;
}

/**
 * The fan-in the drawing shows and a screen reader cannot see (ISC-396): the number of ingest
 * sources and the first stage after the last of them in run order — DEDUPE on the live workflow.
 * Null without a source or without a stage after them, so the sentence is never half true.
 */
export function fanIn(workflow: WorkflowView | null | undefined): FanIn | null {
    const all = workflow?.phases.flatMap((phase) => phase.stages) ?? [];
    const count = all.filter((stage) => stage.kind === 'ingest').length;
    const target = all.slice(all.map((stage) => stage.kind).lastIndexOf('ingest') + 1)[0];
    if (count === 0 || target === undefined) {
        return null;
    }
    // The stage after the last source is never itself a source, so it always has a catalog key.
    return {count, targetKey: stageLabelKey(target) ?? target.id};
}
