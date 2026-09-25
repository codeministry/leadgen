import {LastRunView} from '@core/model/last-run';
import {WorkflowStage} from '@core/model/workflow';
import {LgIconName} from '@shared/icon/lucide-icons';
import {isAiStage} from './ai-stage';

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

/**
 * One icon per kind of sub-node an expanded stage opens (ISC-406): a knockout, each of SCORE's
 * four blocks, and a prompt. Shared by the sub-node card on the canvas and the heading of the
 * section it opens in the sheet, so the two cannot disagree.
 */
export const SUB_ICONS = {
    knockout: 'ban',
    weights: 'scale',
    penalties: 'circle-minus',
    bands: 'chart-column',
    topics: 'tags',
    prompt: 'message-square-text',
} as const satisfies Readonly<Record<string, LgIconName>>;

/**
 * One icon per knockout, keyed by the wire id `FilterStage.id()` sends. The kind icon above
 * says only "something was rejected here", which is the one thing every knockout has in common
 * and therefore the one thing worth nothing on a card that already sits under FILTER: six
 * sub-nodes all carrying the same crossed circle name the group, never the criterion. These
 * name what was measured instead — where the work is, how much of it is remote, which stack,
 * which skill, which contract.
 *
 * A knockout the server adds later falls back to the kind icon rather than to a neutral glyph:
 * the crossed circle is still true of it, only unspecific.
 */
export const KNOCKOUT_ICONS = {
    abroad: 'globe',
    'remote-share': 'house',
    'out-of-reach': 'map-pin',
    'role-or-stack': 'layers',
    'no-core-skill': 'puzzle',
    'contract-form': 'handshake',
} as const satisfies Readonly<Record<string, LgIconName>>;

/** The icon of one knockout by its wire id; an unknown one keeps the kind icon. */
export function knockoutIcon(id: string): LgIconName {
    return (KNOCKOUT_ICONS as Readonly<Record<string, LgIconName>>)[id] ?? SUB_ICONS.knockout;
}

/** The icon of a sub-node by its id (`knockout:<id>`, `score:<block>`, `prompt:<id>`); a block the server adds later gets a neutral glyph. */
export function subIcon(subId: string): LgIconName {
    const [prefix = '', rest = ''] = subId.split(':', 2);
    if (prefix === 'knockout') return knockoutIcon(rest);
    if (prefix === 'prompt') return SUB_ICONS[prefix];
    return (SUB_ICONS as Readonly<Record<string, LgIconName>>)[rest] ?? 'ellipsis';
}

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

/**
 * How many adverts the stage works on at once, only when that is more than one: a width of one,
 * or none, draws the node exactly as a stage no width bounds (ISC-402).
 */
export function stackWidth(stage: WorkflowStage): number | null {
    const value = stage.width?.value ?? null;
    return value !== null && value > 1 ? value : null;
}

/**
 * The ids of every marker a node of this stage draws, in the legend's vocabulary: its cost
 * classes, `ai`, `failed` and `width`. Built from the predicates the node draws with, so the
 * legend's answer to a hovered node (ISC-405) cannot disagree with the node.
 */
export function markersOf(stage: WorkflowStage, failed: boolean): ReadonlySet<string> {
    const markers = new Set<string>(stage.costClasses);
    if (isAiStage(stage)) {
        markers.add('ai');
    }
    if (failed) {
        markers.add('failed');
    }
    if (stackWidth(stage) !== null) {
        markers.add('width');
    }
    return markers;
}
