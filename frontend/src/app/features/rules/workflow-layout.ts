import dagre from '@dagrejs/dagre';
import {WorkflowStage, WorkflowView} from '@core/model/workflow';

/** A node's box in px — the canvas binds it on each node, because html nodes ignore their own. */
export interface NodeSize {
    readonly width: number;
    readonly height: number;
}

/** The px box per node kind. Sub-node kinds join here when stages expand. */
export interface WorkflowLayoutSizes {
    readonly stage: NodeSize;
}

/** One positioned node; `x`/`y` is the top-left corner, which is what ngx-vflow places. */
export interface LayoutNode {
    readonly id: string;
    /** The server's stage id (`INGEST <sourceId>`, `DEDUPE` …) the node draws. */
    readonly stageId: string;
    readonly x: number;
    readonly y: number;
    readonly width: number;
    readonly height: number;
}

export interface LayoutEdge {
    readonly id: string;
    readonly source: string;
    readonly target: string;
}

export interface WorkflowLayout {
    readonly nodes: readonly LayoutNode[];
    readonly edges: readonly LayoutEdge[];
}

/** Stable node id, derived from the server's ids: the source for an ingest entry, the stage otherwise. */
function nodeId(stage: WorkflowStage): string {
    return stage.kind === 'ingest' && stage.sourceId !== null ? `ingest:${stage.sourceId}` : `stage:${stage.id}`;
}

/** Gap between nodes of one rank, and between ranks, in px; the canvas tunes both once it renders. */
const NODE_GAP = 24;
const RANK_GAP = 56;

function edge(source: string, target: string): LayoutEdge {
    return {id: `${source}->${target}`, source, target};
}

/**
 * The workflow as a left-to-right graph. Every ingest entry is its own node on the rank before
 * the first non-ingest stage (DEDUPE) with one edge into it; the other stages follow in the
 * server's order, one edge per consecutive pair. "Read by nothing" is not part of the flow.
 *
 * Pure and synchronous. `expanded` names the stages whose sub-nodes are open; none are drawn yet.
 */
export function layoutWorkflow(
    workflow: WorkflowView,
    expanded: ReadonlySet<string>,
    sizes: WorkflowLayoutSizes,
): WorkflowLayout {
    void expanded;
    const all = workflow.phases.flatMap((phase) => phase.stages);
    const sources = all.filter((stage) => stage.kind === 'ingest');
    const chain = all.filter((stage) => stage.kind !== 'ingest');

    const edges: LayoutEdge[] = [];
    const first = chain[0];
    if (first !== undefined) for (const source of sources) edges.push(edge(nodeId(source), nodeId(first)));
    for (let i = 1; i < chain.length; i++) {
        const from = chain[i - 1];
        const to = chain[i];
        if (from !== undefined && to !== undefined) edges.push(edge(nodeId(from), nodeId(to)));
    }

    const graph = new dagre.graphlib.Graph();
    graph.setGraph({rankdir: 'LR', nodesep: NODE_GAP, ranksep: RANK_GAP});
    graph.setDefaultEdgeLabel(() => ({}));
    const {width, height} = sizes.stage;
    for (const stage of all) graph.setNode(nodeId(stage), {width, height});
    for (const e of edges) graph.setEdge(e.source, e.target);
    dagre.layout(graph);

    // dagre orders one rank by its own heuristics; the sources keep the server's order top to bottom.
    const sourceYs = sources.map((stage) => graph.node(nodeId(stage)).y).sort((a, b) => a - b);
    const sourceY = new Map(sources.map((stage, i) => [nodeId(stage), sourceYs[i] ?? 0]));

    // dagre answers node centres; the canvas places top-left corners.
    const nodes = all.map((stage): LayoutNode => {
        const id = nodeId(stage);
        const {x} = graph.node(id);
        const y = sourceY.get(id) ?? graph.node(id).y;
        return {id, stageId: stage.id, x: x - width / 2, y: y - height / 2, width, height};
    });
    return {nodes, edges};
}
