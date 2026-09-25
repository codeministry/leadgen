import {RulesView} from '@core/model/rules-view';
import {WorkflowStage, WorkflowView} from '@core/model/workflow';

/** A node's box in px — the canvas binds it on each node, because html nodes ignore their own. */
export interface NodeSize {
    readonly width: number;
    readonly height: number;
}

/** The px box per node kind: `stage` for a step of the run, `sub` for anything an expanded stage opens. */
export interface WorkflowLayoutSizes {
    readonly stage: NodeSize;
    readonly sub: NodeSize;
}

/**
 * What a node draws: a step of the run, one hard filter of FILTER, one block of SCORE's rule set,
 * or the prompt a stage sends.
 */
export type LayoutNodeKind = 'stage' | 'knockout' | 'score-block' | 'prompt';

/** One positioned node; `x`/`y` is the top-left corner, which is what ngx-vflow places. */
export interface LayoutNode {
    readonly id: string;
    /** The server's stage id (`INGEST <sourceId>`, `DEDUPE` …) the node draws, or opened from for a sub-node. */
    readonly stageId: string;
    readonly kind: LayoutNodeKind;
    /** The node id of the stage a sub-node opened from; null on a stage. */
    readonly parent: string | null;
    readonly x: number;
    readonly y: number;
    readonly width: number;
    readonly height: number;
}

/** A side of a node's box, as ngx-vflow names a handle's position. */
export type NodeSide = 'top' | 'right' | 'bottom' | 'left';

/**
 * How an edge runs through the columns. `within` joins two stages of one phase, stacked in one
 * column; `across` leaves a column for the next one, which is also every source's edge into
 * DEDUPE; `sub` hangs a sub-node off the stage that opened it.
 */
export type EdgeKind = 'within' | 'across' | 'sub';

export interface LayoutEdge {
    readonly id: string;
    readonly source: string;
    readonly target: string;
    readonly kind: EdgeKind;
    /** Where the edge leaves the source node's box. */
    readonly sourceSide: NodeSide;
    /** Where the edge enters the target node's box. */
    readonly targetSide: NodeSide;
}

export interface WorkflowLayout {
    readonly nodes: readonly LayoutNode[];
    readonly edges: readonly LayoutEdge[];
}

/** A node before the layout has placed it. */
interface Unplaced {
    readonly id: string;
    readonly stageId: string;
    readonly kind: LayoutNodeKind;
    readonly parent: string | null;
    readonly size: NodeSize;
}

/** Stable node id, derived from the server's ids: the source for an ingest entry, the stage otherwise. */
function nodeId(stage: WorkflowStage): string {
    return stage.kind === 'ingest' && stage.sourceId !== null ? `ingest:${stage.sourceId}` : `stage:${stage.id}`;
}

/**
 * The column geometry in px. `COLUMN_GAP` is the free space between two phase columns, wide
 * enough for the edge that runs from one column's last stage into the next column's first;
 * `ROW_GAP` separates two stages of one column, `SUB_GAP` a sub-node from what is above it, and
 * `SUB_INDENT` shifts the sub-nodes right so they read as belonging to the stage above them.
 */
const COLUMN_GAP = 72;
const ROW_GAP = 28;
const SUB_GAP = 10;
const SUB_INDENT = 16;

/**
 * The x, from a stage's left edge, of the rail its sub-edges run down: the middle of the indent,
 * so the rail stays clear of the sub-nodes' boxes and each edge turns right into its node's left.
 */
export const SUB_RAIL_X = SUB_INDENT / 2;

/**
 * The sides per kind. A column reads top to bottom, so within a phase the edge is a short
 * vertical connector; between columns it runs left to right; a sub-edge leaves the stage's bottom
 * on the rail and enters the sub-node from the left, which reads as an outline rather than a loop.
 */
const SIDES: Readonly<Record<EdgeKind, {sourceSide: NodeSide; targetSide: NodeSide}>> = {
    within: {sourceSide: 'bottom', targetSide: 'top'},
    across: {sourceSide: 'right', targetSide: 'left'},
    sub: {sourceSide: 'bottom', targetSide: 'left'},
};

function edge(source: string, target: string, kind: EdgeKind): LayoutEdge {
    return {id: `${source}->${target}`, source, target, kind, ...SIDES[kind]};
}

/**
 * SCORE's blocks in the order a score is built: what adds, what subtracts, where the sum lands,
 * what the profile steers by. A block with nothing in it is not drawn; `bands` always holds its
 * three thresholds.
 */
function scoreBlocks(rules: RulesView): string[] {
    const blocks: string[] = [];
    if (rules.weights.length > 0) blocks.push('weights');
    if (rules.penalties.length > 0) blocks.push('penalties');
    blocks.push('bands');
    if (rules.interestTopics.length + rules.disinterestTopics.length > 0) blocks.push('topics');
    return blocks;
}

/**
 * What an expanded stage opens, read from the typed payloads and never from key prefixes: its
 * knockouts in `FilterStage` order, SCORE's blocks from the rule set, and the prompt it sends.
 */
function subNodes(stage: WorkflowStage, rules: RulesView | null, size: NodeSize): Unplaced[] {
    const parent = nodeId(stage);
    const sub = (id: string, kind: LayoutNodeKind): Unplaced => ({id, stageId: stage.id, kind, parent, size});
    const subs: Unplaced[] = [];
    for (const knockout of stage.knockouts ?? []) subs.push(sub(`knockout:${knockout.id}`, 'knockout'));
    if (stage.id === 'SCORE' && rules !== null) {
        for (const block of scoreBlocks(rules)) subs.push(sub(`score:${block}`, 'score-block'));
    }
    if (stage.promptId !== null) subs.push(sub(`prompt:${stage.promptId}`, 'prompt'));
    return subs;
}

/**
 * Whether expanding the stage would open anything: the question the canvas asks before it
 * offers a toggle, answered by the same `subNodes` the layout places, so the two cannot differ.
 */
export function opensSubNodes(stage: WorkflowStage, rules: RulesView | null): boolean {
    return subNodes(stage, rules, {width: 0, height: 0}).length > 0;
}

/**
 * The workflow as a diagram in phase columns: the phases stand left to right in the server's
 * order, and the stages of one phase stack top to bottom in the server's order. Every ingest
 * entry is its own node in its phase's column with one edge into the first non-ingest stage
 * (DEDUPE); the other stages are joined one edge per consecutive pair, which is what carries the
 * flow from the bottom of one column to the top of the next. Columns are top-aligned, so every
 * phase starts on the same line. "Read by nothing" is not part of the flow.
 *
 * `expanded` names the stages, by server id, whose sub-nodes are open. They stack directly below
 * their stage inside the same column, indented by `SUB_INDENT`, and push the later stages of that
 * column down; no stage ever changes column or its place in the phase. Each sub-node hangs off
 * its stage by one edge. Every edge carries its kind and the sides it leaves and enters by, read
 * off the columns: `within` a phase bottom to top, `across` phases right to left, and `sub` from
 * the stage's bottom on the rail at `SUB_RAIL_X` into the sub-node's left. SCORE's blocks need `rules`: without a rule set SCORE opens only its
 * prompt. Pure and synchronous — a fixed topology needs no graph-layout engine.
 */
export function layoutWorkflow(
    workflow: WorkflowView,
    expanded: ReadonlySet<string>,
    sizes: WorkflowLayoutSizes,
    rules: RulesView | null = null,
): WorkflowLayout {
    const all = workflow.phases.flatMap((phase) => phase.stages);
    const sources = all.filter((stage) => stage.kind === 'ingest');
    const chain = all.filter((stage) => stage.kind !== 'ingest');

    const phaseOf = new Map<WorkflowStage, number>();
    workflow.phases.forEach((phase, i) => {
        for (const stage of phase.stages) phaseOf.set(stage, i);
    });
    const kindOf = (from: WorkflowStage, to: WorkflowStage): EdgeKind =>
        phaseOf.get(from) === phaseOf.get(to) ? 'within' : 'across';

    const edges: LayoutEdge[] = [];
    const first = chain[0];
    if (first !== undefined) {
        for (const source of sources) edges.push(edge(nodeId(source), nodeId(first), kindOf(source, first)));
    }
    for (let i = 1; i < chain.length; i++) {
        const from = chain[i - 1];
        const to = chain[i];
        if (from !== undefined && to !== undefined) edges.push(edge(nodeId(from), nodeId(to), kindOf(from, to)));
    }

    const columnWidth = Math.max(sizes.stage.width, SUB_INDENT + sizes.sub.width);
    const stages: LayoutNode[] = [];
    const subs: LayoutNode[] = [];
    let x = 0;
    for (const phase of workflow.phases) {
        if (phase.stages.length === 0) continue;
        let y = 0;
        for (const stage of phase.stages) {
            const id = nodeId(stage);
            const {width, height} = sizes.stage;
            stages.push({id, stageId: stage.id, kind: 'stage', parent: null, x, y, width, height});
            y += height;
            // a source that asks a model opens its prompt like any other stage
            if (expanded.has(stage.id)) {
                for (const sub of subNodes(stage, rules, sizes.sub)) {
                    y += SUB_GAP;
                    const {kind, parent, size} = sub;
                    subs.push({id: sub.id, stageId: sub.stageId, kind, parent, x: x + SUB_INDENT, y, ...size});
                    edges.push(edge(id, sub.id, 'sub'));
                    y += size.height;
                }
            }
            y += ROW_GAP;
        }
        x += columnWidth + COLUMN_GAP;
    }

    // the stages first in the server's order, then the sub-nodes, so opening one never renumbers them
    return {nodes: [...stages, ...subs], edges};
}
