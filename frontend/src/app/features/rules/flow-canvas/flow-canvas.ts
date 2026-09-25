import {
    ChangeDetectionStrategy,
    Component,
    computed,
    effect,
    ElementRef,
    inject,
    input,
    model,
    signal,
    untracked,
    viewChild,
} from '@angular/core';
import {TranslocoPipe} from '@jsverse/transloco';
import {LastRunView} from '@core/model/last-run';
import {RulesView} from '@core/model/rules-view';
import {WorkflowStage, WorkflowView} from '@core/model/workflow';
import {Icon} from '@shared/icon/icon';
import {Edge, HtmlTemplateNode, Vflow, VflowComponent, ViewportState} from 'ngx-vflow';
import {FlowNode} from '../flow-node/flow-node';
import {stageCounts} from '../stage-count';
import {failedStageIds} from '../stage-marks';
import {
    LayoutEdge,
    LayoutNode,
    layoutWorkflow,
    opensSubNodes,
    NodeSide,
    SUB_RAIL_X,
    WorkflowLayout,
    WorkflowLayoutSizes,
} from '../workflow-layout';

/**
 * One anchor on a node's box, named after the edge kind it serves (`within-out`, `across-in` …).
 * `offsetX` is ngx-vflow's: it moves a top or bottom handle left of the box's centre.
 */
interface FlowHandle {
    readonly id: string;
    readonly type: 'source' | 'target';
    readonly position: NodeSide;
    readonly offsetX: number;
}

/** A step of the run, drawn by `lg-flow-node`. */
interface StageNodeData {
    readonly kind: 'stage';
    readonly id: string;
    readonly stage: WorkflowStage;
    readonly phaseId: string;
    readonly width: number;
    readonly height: number;
    readonly handles: readonly FlowHandle[];
}

/**
 * What an expanded stage opens: a knockout named by the server's prose (`text`), or a SCORE
 * block or a prompt named by a catalog key.
 */
interface SubNodeData {
    readonly kind: 'sub';
    readonly id: string;
    readonly text: string | null;
    readonly key: string | null;
    readonly width: number;
    readonly height: number;
    readonly handles: readonly FlowHandle[];
}

const sourceHandleOf = (edge: LayoutEdge): string => `${edge.kind}-out`;
const targetHandleOf = (edge: LayoutEdge): string => `${edge.kind}-in`;

/**
 * The anchors each node needs, read off the layout's edges: a node carries a handle only on the
 * sides an edge actually leaves or enters it by. A sub-edge leaves its stage's bottom on the rail
 * at `SUB_RAIL_X` rather than at the centre, which is where the within-phase edge leaves.
 */
function handlesOf(layout: WorkflowLayout): ReadonlyMap<string, readonly FlowHandle[]> {
    const width = new Map(layout.nodes.map((node) => [node.id, node.width]));
    const handles = new Map<string, Map<string, FlowHandle>>();
    const add = (nodeId: string, handle: FlowHandle) => {
        const own = handles.get(nodeId) ?? new Map<string, FlowHandle>();
        own.set(handle.id, handle);
        handles.set(nodeId, own);
    };
    for (const edge of layout.edges) {
        const rail = edge.kind === 'sub' ? (width.get(edge.source) ?? 0) / 2 - SUB_RAIL_X : 0;
        add(edge.source, {id: sourceHandleOf(edge), type: 'source', position: edge.sourceSide, offsetX: rail});
        add(edge.target, {id: targetHandleOf(edge), type: 'target', position: edge.targetSide, offsetX: 0});
    }
    return new Map([...handles].map(([id, own]) => [id, [...own.values()]]));
}

/**
 * How far the span [`start`, `end`] moves to lie inside [`min`, `max`]: zero when it already
 * does, and aligned to `min` when the room is smaller than the span.
 */
function shiftInto(start: number, end: number, min: number, max: number): number {
    if (start >= min && end <= max) return 0;
    if (start < min || end - start > max - min) return min - start;
    return max - end;
}

type FlowNodeData = StageNodeData | SubNodeData;

/**
 * The px box per node kind. In px rather than rem because the layout computes in px and ngx-vflow
 * html nodes ignore their own width/height; the template binds these on the node itself. A
 * stage holds `lg-flow-node`'s three lines: phase, name, markers and count.
 */
export const FLOW_NODE_SIZES: WorkflowLayoutSizes = {
    stage: {width: 184, height: 92},
    sub: {width: 160, height: 44},
};

/** How far the canvas zooms. The floor is low enough to fit the whole live workflow at 1440 px. */
export const FLOW_ZOOM = {min: 0.2, max: 2, step: 1.25} as const;

/** Padding around the graph after a fit, as a fraction of the box. */
const FIT_PADDING = 0.06;

/** The clearance a revealed node keeps from the edges of the part of the box it is panned into, in px. */
const REVEAL_MARGIN = 16;

/** SCORE's blocks by catalog key, spelled out so every key is a literal the catalog check sees. */
const SCORE_BLOCK_KEYS: Readonly<Record<string, string>> = {
    weights: 'rules.weights',
    penalties: 'rules.penalties',
    bands: 'rules.thresholds',
    topics: 'rules.canvas.topics',
};

/**
 * The workflow from `/api/v1/workflow` as a graph in phase columns in a box of its own: every
 * ingest source feeds DEDUPE, the stages follow in the server's order, and each stage is an
 * `lg-flow-node` carrying what the last run did there.
 *
 * The box pans and zooms and never moves the page: it fits the whole graph once the nodes are
 * measured, offers zoom in, zoom out and fit as named buttons, and swallows every wheel it
 * receives — d3-zoom stops calling `preventDefault` at its zoom limits, and the page would
 * scroll from there.
 */
@Component({
    selector: 'lg-flow-canvas',
    imports: [Vflow, FlowNode, Icon, TranslocoPipe],
    templateUrl: './flow-canvas.html',
    styleUrl: './flow-canvas.css',
    changeDetection: ChangeDetectionStrategy.OnPush,
})
export class FlowCanvas {
    readonly workflow = input.required<WorkflowView>();
    /** The recorded run, for the count chips and the failed marker; null before one finished. */
    readonly lastRun = input<LastRunView | null>(null);
    /** The rule set, which SCORE's blocks are read from when SCORE is expanded. */
    readonly rules = input<RulesView | null>(null);
    /** The stage id the screen shows, marked on its node. */
    readonly selected = input<string | null>(null);
    /**
     * What covers part of the box from outside it — the stage sheet, fixed at the window's right
     * edge. Measured once per selection rather than watched: the canvas pans the selected node
     * into the part this element leaves free, and never moves when the node is already there.
     */
    readonly occluder = input<HTMLElement | null>(null);
    /**
     * Stage ids whose sub-nodes are open. View state, not selection: it lives here and is
     * flipped by the node's own toggle, and it is a `model` only so a host may seed or read it.
     * It stays out of the URL on purpose — the `stage` query parameter is the screen's one
     * shareable state, and what is folded open is how this reader happens to look at it.
     */
    readonly expanded = model<ReadonlySet<string>>(new Set<string>());

    protected readonly zoom = FLOW_ZOOM;

    private readonly vflow = viewChild.required(VflowComponent);
    private readonly box = inject<ElementRef<HTMLElement>>(ElementRef);
    /** The viewport a zoom button set during this frame, before the library has published it. */
    private pending: ViewportState | null = null;

    private readonly layout = computed(() => layoutWorkflow(this.workflow(), this.expanded(), FLOW_NODE_SIZES, this.rules()));

    private readonly counts = computed(() => stageCounts(this.workflow(), this.lastRun()));
    private readonly failedIds = computed(() => failedStageIds(this.lastRun()));

    readonly nodes = computed((): HtmlTemplateNode<FlowNodeData>[] => {
        const stages = new Map<string, {stage: WorkflowStage; phaseId: string}>();
        for (const phase of this.workflow().phases) {
            for (const stage of phase.stages) stages.set(stage.id, {stage, phaseId: phase.id});
        }
        const layout = this.layout();
        const handles = handlesOf(layout);
        return layout.nodes.flatMap((node) => {
            const owner = stages.get(node.stageId);
            if (owner === undefined) return [];
            return [
                {
                    id: node.id,
                    type: 'html-template',
                    point: signal({x: node.x, y: node.y}),
                    width: signal(node.width),
                    height: signal(node.height),
                    draggable: signal(false),
                    data: signal(this.dataOf(node, owner.stage, owner.phaseId, handles.get(node.id) ?? [])),
                },
            ];
        });
    });

    readonly edges = computed((): Edge[] =>
        this.layout().edges.map((edge) => ({
            id: edge.id,
            source: edge.source,
            target: edge.target,
            sourceHandle: sourceHandleOf(edge),
            targetHandle: targetHandleOf(edge),
            type: 'template',
            // `currentColor` resolves to the canvas's edge token; the library's own default is a hex.
            markers: signal({end: {type: 'arrow-closed', color: 'currentColor', width: 14, height: 14}}),
        })),
    );

    constructor() {
        // Fit once the library has measured the nodes, and again whenever the workflow itself
        // changes. Opening or closing a stage keeps the viewport: the reader just pointed at
        // that stage, and a refit would move it out from under the pointer — the sub-nodes land
        // below it, and the fit button is one press away when they run off the box.
        effect(() => {
            const vflow = this.vflow();
            if (!vflow.initialized()) return;
            this.workflow();
            untracked(() => this.fit());
        });
        // Pan the selected node out from under the occluder, once per selection and two frames
        // on, after the library has published the viewport — a fit on the same tick included.
        // Nothing on close: with no occluder there is nothing to get out from under.
        effect((onCleanup) => {
            const vflow = this.vflow();
            if (!vflow.initialized()) return;
            const selected = this.selected();
            const occluder = this.occluder();
            if (selected === null || occluder === null) return;
            let frame = requestAnimationFrame(() => (frame = requestAnimationFrame(() => this.reveal(selected, occluder))));
            onCleanup(() => cancelAnimationFrame(frame));
        });
    }

    protected expandable(stage: WorkflowStage): boolean {
        return opensSubNodes(stage, this.rules());
    }

    protected isExpanded(stageId: string): boolean {
        return this.expanded().has(stageId);
    }

    /** Opens the stage's sub-nodes, or closes them; the layout re-runs from `expanded`. */
    toggle(stageId: string): void {
        this.expanded.update((open) => {
            const next = new Set(open);
            if (!next.delete(stageId)) next.add(stageId);
            return next;
        });
    }

    protected count(stageId: string): number | null {
        return this.counts()[stageId] ?? null;
    }

    protected failed(stageId: string): boolean {
        return this.failedIds().has(stageId);
    }

    fit(): void {
        this.vflow().fitView({padding: FIT_PADDING, duration: 0});
    }

    zoomIn(): void {
        this.zoomBy(FLOW_ZOOM.step);
    }

    zoomOut(): void {
        this.zoomBy(1 / FLOW_ZOOM.step);
    }

    /**
     * Pans, at the current zoom, so the node drawn for `stageId` lies inside the part of the box
     * `occluder` leaves free, with a margin; the left and top edges win when that part is smaller
     * than the node. Measured from the rendered boxes, so a stage the canvas does not draw, or a
     * DOM with no layout (jsdom), moves nothing. Only the viewport moves, never the page.
     */
    private reveal(stageId: string, occluder: HTMLElement): void {
        const host = this.box.nativeElement;
        // Matched on the dataset rather than a selector: an ingest id carries a space.
        const anchor = Array.from(host.querySelectorAll<HTMLElement>('lg-flow-node [data-stage]')).find((a) => a.dataset['stage'] === stageId);
        const node = anchor?.closest('[data-node]')?.getBoundingClientRect();
        if (node === undefined || node.width === 0) return;
        const box = host.getBoundingClientRect();
        const cover = occluder.getBoundingClientRect();
        const covers = cover.width > 0 && cover.left < box.right && cover.right > box.left && cover.top < box.bottom && cover.bottom > box.top;
        const right = covers ? Math.max(box.left, cover.left) : box.right;
        const dx = shiftInto(node.left, node.right, box.left + REVEAL_MARGIN, right - REVEAL_MARGIN);
        const dy = shiftInto(node.top, node.bottom, box.top + REVEAL_MARGIN, box.bottom - REVEAL_MARGIN);
        if (dx === 0 && dy === 0) return;
        const vflow = this.vflow();
        const {x, y, zoom} = this.pending ?? vflow.viewport();
        const target: ViewportState = {zoom, x: x + dx, y: y + dy};
        if (this.pending === null) requestAnimationFrame(() => (this.pending = null));
        this.pending = target;
        vflow.viewportTo(target);
    }

    /** A wheel over the box is the canvas's alone, even where d3-zoom lets it through. */
    protected keepWheel(event: WheelEvent): void {
        event.preventDefault();
    }

    /**
     * Zooms about the centre of the box, not the graph's origin, clamped to the zoom range. The
     * library publishes a new viewport a frame after it is set, so clicks inside one frame build
     * on the last viewport this method set rather than on the stale published one.
     */
    private zoomBy(factor: number): void {
        const vflow = this.vflow();
        const {x, y, zoom} = this.pending ?? vflow.viewport();
        const next = Math.min(FLOW_ZOOM.max, Math.max(FLOW_ZOOM.min, zoom * factor));
        const host = this.box.nativeElement;
        const cx = host.clientWidth / 2;
        const cy = host.clientHeight / 2;
        const target: ViewportState = {zoom: next, x: cx - ((cx - x) / zoom) * next, y: cy - ((cy - y) / zoom) * next};
        if (this.pending === null) requestAnimationFrame(() => (this.pending = null));
        this.pending = target;
        vflow.viewportTo(target);
    }

    private dataOf(node: LayoutNode, stage: WorkflowStage, phaseId: string, handles: readonly FlowHandle[]): FlowNodeData {
        const box = {id: node.id, width: node.width, height: node.height, handles};
        switch (node.kind) {
            case 'stage':
                return {...box, kind: 'stage', stage, phaseId};
            case 'knockout': {
                const id = node.id.replace(/^knockout:/, '');
                const text = stage.knockouts?.find((knockout) => knockout.id === id)?.description ?? id;
                return {...box, kind: 'sub', text, key: null};
            }
            case 'score-block': {
                const block = node.id.replace(/^score:/, '');
                const key = SCORE_BLOCK_KEYS[block] ?? null;
                return {...box, kind: 'sub', text: key === null ? block : null, key};
            }
            case 'prompt':
                return {...box, kind: 'sub', text: null, key: `rules.prompt.${stage.promptId ?? ''}`};
        }
    }
}
