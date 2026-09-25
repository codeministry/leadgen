import {LastRunView} from '@core/model/last-run';
import {WorkflowStage} from '@core/model/workflow';
import {LgIconName} from '@shared/icon/lucide-icons';

/**
 * One icon per cost class, spelled out as a literal map. The meaning travels in the icon's
 * accessible name, never in a colour: `free`, `model`, `network` and `file` are what a stage
 * costs, not how good it is, and the signal colour is reserved for survivors.
 *
 * Shared by the stage rail and the flow graph's nodes, so the two drawings of one stage cannot
 * disagree about what its icons are or what they say.
 */
export const COST_ICONS: Readonly<Record<string, LgIconName>> = {
    free: 'list-checks',
    model: 'message-circle-question',
    network: 'external-link',
    file: 'file-text',
};

/** The AI marker (ISC-308) and the failed-stage marker, named once for every drawing and its legend. */
export const AI_ICON: LgIconName = 'sparkles';
export const FAILED_ICON: LgIconName = 'triangle-alert';

/** The icon of one cost class; a class the server adds later gets a neutral glyph, never none. */
export function costIcon(costClass: string): LgIconName {
    return COST_ICONS[costClass] ?? 'ellipsis';
}

/**
 * What a cost icon says on a stage. The model class covers two different calls, a prompt to a
 * language model and an embedding, so a stage names the one it makes; the legend keeps the
 * general word, because it explains the icon rather than a stage.
 */
export function costLabelKey(stage: WorkflowStage, costClass: string): string {
    if (costClass !== 'model') {
        return `rules.cost.${costClass}`;
    }
    return stage.promptId === null ? 'rules.cost.modelEmbed' : 'rules.cost.modelPrompt';
}

/**
 * The catalog key of a stage's name, or `null` for an ingest stage: that one is named by its
 * source, which is configuration and not a label. Every other stage has a closed id the server
 * sends and the browser holds the words for.
 */
export function stageLabelKey(stage: WorkflowStage): string | null {
    return stage.kind === 'ingest' ? null : `rules.stage.${stage.id.toLowerCase()}`;
}

/**
 * The stage ids the recorded run's `stages[]` marks `FAILED` — the same strings as `stage.id`.
 * Empty without a run. Read by the rail and the graph, so both mark the same stage.
 */
export function failedStageIds(run: LastRunView | null): ReadonlySet<string> {
    if (run === null) {
        return new Set();
    }
    return new Set(run.stages.filter((stage) => stage.status === 'FAILED').map((stage) => stage.stage));
}
