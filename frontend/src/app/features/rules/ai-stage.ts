import {WorkflowStage} from '@core/model/workflow';

/**
 * Whether a language model takes part in this stage (ISC-308): the `model` cost class, or an
 * ingest source that sends the extraction prompt. One predicate, read by the rail's marker and
 * the detail pane's band, so the two halves of the screen cannot disagree.
 */
export function isAiStage(stage: WorkflowStage): boolean {
    return stage.costClasses.includes('model') || (stage.kind === 'ingest' && stage.promptId !== null);
}
