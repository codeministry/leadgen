import {
    ChangeDetectionStrategy,
    Component,
    computed,
    effect,
    ElementRef,
    inject,
    input,
    model,
    output,
    signal,
    untracked,
    viewChild,
} from '@angular/core';
import {TranslocoPipe} from '@jsverse/transloco';
import {LastRunView} from '@core/model/last-run';
import {RulesView} from '@core/model/rules-view';
import {WorkflowStage, WorkflowView} from '@core/model/workflow';
import {Icon} from '@shared/icon/icon';
import {Curve, CurveFactory, Edge, HtmlTemplateNode, Vflow, VflowComponent, ViewportState} from 'ngx-vflow';
import {FlowNode} from '../flow-node/flow-node';
import {stageCounts} from '../stage-count';
import {LgIconName} from '@shared/icon/lucide-icons';
import {RouterLink} from '@angular/router';
import {failedStageIds, subIcon} from '../stage-marks';
import {RunState, StageRunState} from '../run-state';
import {
    EdgeKind,
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
    /** This stage's place against the run in flight, from `run().states`; null without a pass. */
    readonly state: StageRunState | null;
    /** Seconds spent in the running stage (ISC-412); null on every node but the running one. */
    readonly elapsed: number | null;
}

/**
 * What an expanded stage opens: a knockout named by the server's prose (`text`), or a SCORE
 * block or a prompt named by a catalog key.
 */
interface SubNodeData {
    readonly kind: 'sub';
    /** The node id, which is also the `section` query parameter its link sets. */
    readonly id: string;
    /** The server id of the stage it opened from, the link's `stage`. */
    readonly stageId: string;
    readonly icon: LgIconName;
    readonly text: string | null;
    readonly key: string | null;
    readonly width: number;
    readonly height: number;
    readonly handles: readonly FlowHandle[];
}

/**
 * A sub-edge as a file-tree branch: from the rail point on the stage's bottom straight down to the
 * sub-node's middle, then right into its left edge. The library's `step` curve pushes a fixed
 * offset out of each handle, which overshoots an indent this narrow and doubles back in a zig-zag.
 */
const treeBranch: CurveFactory = (params) => {
    const {sourcePoint: from, targetPoint: to} = params;
    if (params.mode !== 'edge') return {path: `M ${from.x},${from.y} V ${to.y} H ${to.x}`};
    // From the layout's own boxes rather than the handles: the library sets a bottom handle's point
    // a few px below the box, which would leave a gap between the card and its rail.
    const box = (id: string) => {
        const node = params.allNodes.find((n) => n.id === id);
        if (node === undefined) return null;
        const size = node as {width?: () => number; height?: () => number};
        return {...node.point(), width: size.width?.() ?? 0, height: size.height?.() ?? 0};
    };
    const parent = box(params.edge.source);
    const sub = box(params.edge.target);
    if (parent === null || sub === null) return {path: `M ${from.x},${from.y} V ${to.y} H ${to.x}`};
    const railX = parent.x + SUB_RAIL_X;
    return {path: `M ${railX},${parent.y + parent.height} V ${sub.y + sub.height / 2} H ${sub.x}`};
};

const sourceHandleOf = (edge: LayoutEdge): string => `${edge.kind}-out`;
const targetHandleOf = (edge: LayoutEdge): string => `${edge.kind}-in`;

/** Where an edge lies against the run in flight, for the paint the edge template adds later. */
export type EdgeRunState = 'behind' | 'ahead' | 'entering';

/** What the edge template paints: where the edge stands against the run, and whether it is drawn. */
export interface EdgePaint {
    readonly run: EdgeRunState | null;
    /**
     * Hidden while its source stage is expanded. The sub-rail runs down the same channel as the
     * connector to the next stage in the column, so an open stage drew two parallel vertical lines
     * and the lower one read as an underline under the sub-steps. The edge stays in the layout —
     * ISC-389 still joins every consecutive pair — and only the paint is withheld.
     */
    readonly eclipsed: boolean;
    /**
     * The eclipsed connector's last stretch, drawn on its own so the arrow into the next stage
     * survives the eclipse (operator, 2026-09-26): withholding the whole line took the arrowhead
     * with it, and the sub-rail that visually replaces it carries none.
     *
     * A path of our own rather than a dash pattern on the hidden one: these connectors turn corners,
     * so "the last tenth of the line" lands in a bend as often as in the approach. Null unless the
     * edge is eclipsed.
     */
    readonly stub: string | null;
}

/** How much of the approach into the next stage the stub draws, in the graph's own units. */
const STUB_LENGTH = 26;

/**
 * Reads an edge's place against the run off its two endpoints' states, never off `RunState.order`
 * itself (that stays `runState`'s business). A stage-to-stage edge's target sits at or after its
 * source in that order by construction, so once both ends carry a state the target's alone tells
 * the edge apart: `'running'` means this edge is the one entering the active stage, `'done'` means
 * the edge is already behind the run, `'pending'` that it is still ahead.
 *
 * <p>A **sub-edge** has no stage at its far end, so it reads its own stage's state instead: the
 * side lines of an expanded stage carry what that stage carries. That is not decoration — with the
 * connector under an open stage eclipsed, the side lines are the run's only trace down it.
 *
 * <p>No state at either end it reads — no pass in flight — answers null, the same "no pass, no
 * mark" rule the nodes follow.
 */
function edgeRunState(kind: EdgeKind, source: StageRunState | null, target: StageRunState | null): EdgeRunState | null {
    const at = kind === 'sub' ? source : target;
    if (source === null || at === null) return null;
    if (at === 'running') return 'entering';
    if (at === 'done') return 'behind';
    return 'ahead';
}

/**
 * The last stretch of an eclipsed connector, straight down into the next stage's top edge.
 *
 * The layout leaves a gap between a stage's last sub-step and the stage below it, and this is what
 * is drawn in it: the arrow's own short run-up, in the same column centre the hidden connector
 * arrives at, so the two read as one line whose middle happens to be behind the sub-steps.
 */
function stubPath(target: LayoutNode | null): string | null {
    if (target === null) {
        return null;
    }
    const x = target.x + target.width / 2;
    return `M ${x} ${target.y - STUB_LENGTH} L ${x} ${target.y}`;
}

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

/** A span of the box in its own px, on each axis. */
interface FreeArea {
    readonly left: number;
    readonly right: number;
    readonly top: number;
    readonly bottom: number;
}

/**
 * The part of `box` that `cover` leaves free, in the box's own px (operator, 2026-09-30). A sheet
 * docked at the right edge over at least half the box's height takes its width off the right; one
 * docked at the bottom over half the width takes its height off the bottom. Anything else, and a
 * cover that would leave less than `min` px, leaves the whole box: a phone's full-width sheet hides
 * the canvas entirely, and bounds squeezed to nothing would pin the graph instead of freeing it.
 */
export function freeArea(box: DOMRect, cover: DOMRect | null, min: number): FreeArea {
    const whole = {left: 0, right: box.width, top: 0, bottom: box.height};
    if (cover === null || cover.width === 0 || cover.height === 0) return whole;
    const overlapX = Math.min(box.right, cover.right) - Math.max(box.left, cover.left);
    const overlapY = Math.min(box.bottom, cover.bottom) - Math.max(box.top, cover.top);
    if (overlapX <= 0 || overlapY <= 0) return whole;
    if (cover.right >= box.right - 1 && cover.left > box.left + 1 && overlapY >= box.height / 2) {
        const right = cover.left - box.left;
        return right >= min ? {...whole, right} : whole;
    }
    if (cover.bottom >= box.bottom - 1 && cover.top > box.top + 1 && overlapX >= box.width / 2) {
        const bottom = cover.top - box.top;
        return bottom >= min ? {...whole, bottom} : whole;
    }
    return whole;
}

type FlowNodeData = StageNodeData | SubNodeData;

/**
 * The px box per node kind. In px rather than rem because the layout computes in px and ngx-vflow
 * html nodes ignore their own width/height; the template binds these on the node itself. A
 * stage holds `lg-flow-node`'s three lines: phase, name, markers and count.
 */
export const FLOW_NODE_SIZES: WorkflowLayoutSizes = {
    stage: {width: 200, height: 92},
    sub: {width: 160, height: 44},
};

/** How far the canvas zooms. The floor is low enough to fit the whole live workflow at 1440 px. */
export const FLOW_ZOOM = {min: 0.2, max: 2, step: 1.25} as const;

/**
 * How much of the graph, in px on each axis, stays inside the box however it is panned (ISC-409).
 * Declared ahead of the clamp so the red run of its probe is a failed assertion, not a missing name.
 */
export const FLOW_PAN_MARGIN = 48;

/** How far one wheel delta px zooms: a mouse notch (~100) is about 14 %, a pinch's small deltas stay continuous. */
const WHEEL_ZOOM_RATE = 0.0015;

/** The most one wheel event zooms, either way, as a fraction. */
const WHEEL_ZOOM_STEP = 0.15;

/** The px a wheel line counts for, when the browser reports its deltas in lines. */
/** How long a set viewport outranks the published one if the library never reports it back. */
const PENDING_MS = 250;
const WHEEL_LINE_PX = 16;

/**
 * Padding around the graph after a fit, as a fraction of the box. Raised from 0.06 on the
 * operator's call (2026-09-26): at 0.06 the graph sat 20px from the top and bottom of the box on a
 * 1920x900 screen, which reads as the drawing pressing against its own frame — and the control
 * column in the corner is inside that frame, so the tightest edge was also the busiest one.
 */
const FIT_PADDING = 0.12;

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
 * measured, offers zoom in, zoom out and fit as named buttons, and takes every wheel it
 * receives away from d3-zoom: a wheel or a two-finger scroll pans, a pinch or Ctrl/Cmd with the
 * wheel zooms about the pointer, and the page never scrolls. Dragging still pans through d3.
 */
@Component({
    selector: 'lg-flow-canvas',
    imports: [Vflow, FlowNode, Icon, RouterLink, TranslocoPipe],
    templateUrl: './flow-canvas.html',
    styleUrl: './flow-canvas.css',
    changeDetection: ChangeDetectionStrategy.OnPush,
})
export class FlowCanvas {
    readonly workflow = input.required<WorkflowView>();
    /** The recorded run, for the count chips and the failed marker; null before one finished. */
    readonly lastRun = input<LastRunView | null>(null);
    /** The run in flight, for the node and edge run marks (ISC-410.2); null when no pass is reported. */
    readonly run = input<RunState | null>(null);
    /**
     * How long the running stage has been running, in whole seconds (ISC-412); null when no
     * stage is running or the elapsed time has not reached this component yet. Reaches only the
     * node whose own state is `running` — `dataOf` reads `run().running` for that, never this
     * input's presence alone, since a run with no stage placed yet leaves every state null too.
     */
    readonly elapsed = input<number | null>(null);
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

    /**
     * Whether the screen currently gives the canvas box the whole window (ISC-407). The screen owns
     * the wrapper; the canvas only asks, through `wideToggle`, and refits whenever the answer
     * changes, because its box just changed by the whole window. No Fullscreen API is involved —
     * see the master's 2026-09-26 decision for why the real full screen was dropped.
     */
    readonly wide = input(false);
    readonly wideToggle = output();

    /**
     * The stages the hovered legend entry names (operator, 2026-09-26). Null leaves every node as
     * it is; a set lights those it holds and dims the rest, so the legend answers in the drawing
     * the way the drawing already answers in the legend (ISC-405, reversed).
     */
    readonly lit = input<ReadonlySet<string> | null>(null);


    protected readonly zoom = FLOW_ZOOM;

    private readonly vflow = viewChild.required(VflowComponent);
    private readonly box = inject<ElementRef<HTMLElement>>(ElementRef);
    private readonly wheelBox = viewChild.required<ElementRef<HTMLElement>>('wheelBox');
    /**
     * The viewport this component last set, until the library has published it. A trackpad's
     * momentum tail arrives sparser than one event a frame and the library can take longer than a
     * frame to publish, so building on the published value then stepped back and flickered.
     */
    private pending: ViewportState | null = null;
    /** Drops `pending` if the library never publishes it, so a drag afterwards is not undone. */
    private pendingTimer: ReturnType<typeof setTimeout> | null = null;

    private readonly layout = computed(() => layoutWorkflow(this.workflow(), this.expanded(), FLOW_NODE_SIZES, this.rules()));

    private readonly counts = computed(() => stageCounts(this.workflow(), this.lastRun()));
    private readonly failedIds = computed(() => failedStageIds(this.lastRun()));

    /**
     * Every layout node's place against the run in flight, keyed by the layout's own node id
     * (`stage:…` / `ingest:…`) rather than by `WorkflowStage.id`, so a lookup by edge endpoint
     * needs no second map. A sub-node's entry is always null — it is not a stage `run().states`
     * ever names.
     */
    private readonly nodeStates = computed((): ReadonlyMap<string, StageRunState | null> => {
        const states = this.run()?.states ?? null;
        return new Map(this.layout().nodes.map((node) => [node.id, node.kind === 'stage' ? (states?.get(node.stageId) ?? null) : null]));
    });

    readonly nodes = computed((): HtmlTemplateNode<FlowNodeData>[] => {
        const stages = new Map<string, {stage: WorkflowStage; phaseId: string}>();
        for (const phase of this.workflow().phases) {
            for (const stage of phase.stages) stages.set(stage.id, {stage, phaseId: phase.id});
        }
        const layout = this.layout();
        const handles = handlesOf(layout);
        const nodeStates = this.nodeStates();
        const elapsed = this.elapsed();
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
                    data: signal(
                        this.dataOf(node, owner.stage, owner.phaseId, handles.get(node.id) ?? [], nodeStates.get(node.id) ?? null, elapsed),
                    ),
                },
            ];
        });
    });

    readonly edges = computed((): Edge<EdgePaint>[] => {
        const nodeStates = this.nodeStates();
        const expanded = this.expanded();
        const placed = new Map(this.layout().nodes.map((node) => [node.id, node]));
        return this.layout().edges.map((edge) => {
            const eclipsed = edge.kind === 'within' && expanded.has(edge.source.replace(/^stage:/, ''));
            return {
                id: edge.id,
                source: edge.source,
                target: edge.target,
                sourceHandle: sourceHandleOf(edge),
                targetHandle: targetHandleOf(edge),
                type: 'template' as const,
                data: signal<EdgePaint>({
                    run: edgeRunState(edge.kind, nodeStates.get(edge.source) ?? null, nodeStates.get(edge.target) ?? null),
                    eclipsed,
                    stub: eclipsed ? stubPath(placed.get(edge.target) ?? null) : null,
                }),
                ...(edge.kind === 'sub'
                    ? // A file tree: down the indent rail, then one branch right into the sub-node. No arrow —
                      // the branch is a few px long, and every sub-edge shares the one vertical line.
                      {curve: signal<Curve>(treeBranch), markers: signal({})}
                    : {
                          // Straight segments with square corners: the operator asked for no rounded lines.
                          curve: signal<Curve>('step'),
                          // `currentColor` resolves to the canvas's edge token; the library's own default is a hex.
                          markers: signal({end: {type: 'arrow-closed' as const, color: 'currentColor', width: 14, height: 14}}),
                      }),
            };
        });
    });

    constructor() {
        // A capture listener rather than a template binding: it has to run before d3-zoom's own
        // wheel listener on the svg below, and it has to be non-passive to prevent the default.
        effect((onCleanup) => {
            const box = this.wheelBox().nativeElement;
            const listener = (event: WheelEvent): void => this.onWheel(event);
            box.addEventListener('wheel', listener, {capture: true, passive: false});
            onCleanup(() => box.removeEventListener('wheel', listener, {capture: true}));
        });
        // Fit once the library has measured the nodes, and again whenever the workflow changes or a
        // stage opens or closes (operator, 2026-09-26). The earlier rule was the opposite — keep the
        // viewport, because the reader had just pointed at that stage — and it was wrong in the case
        // that matters: the sub-nodes land below their stage and ran off the bottom of the box, so
        // the press that opened them was followed by a press on the fit control every time.
        //
        // Two frames, like the size change above: the layout re-runs from `expanded`, and the
        // library has to have measured the new nodes before a fit means anything.
        effect((onCleanup) => {
            const vflow = this.vflow();
            if (!vflow.initialized()) return;
            this.workflow();
            this.expanded();
            let frame = requestAnimationFrame(() => (frame = requestAnimationFrame(() => untracked(() => this.fit()))));
            onCleanup(() => cancelAnimationFrame(frame));
        });
        // A drag pans through d3-zoom inside the library, past `moveTo`, whose behaviour is not
        // reachable for a `translateExtent`. So every viewport the library publishes is clamped the
        // way `moveTo` clamps, and written back when it strayed (ISC-409). A viewport set here
        // arrives clamped already and passes unchanged.
        let published: ViewportState | null = null;
        effect(() => {
            const vflow = this.vflow();
            if (!vflow.initialized()) return;
            const now = vflow.viewport();
            const before = published;
            published = now;
            if (before === null) return;
            untracked(() => {
                // Its own viewport, set through `moveTo`, was clamped there — a reveal less tightly.
                const own = this.pending;
                if (own !== null && Math.abs(own.x - now.x) < 0.5 && Math.abs(own.y - now.y) < 0.5 && Math.abs(own.zoom - now.zoom) < 1e-4) return;
                const clamped = this.clamp(now, before, true);
                if (Math.abs(clamped.x - now.x) > 0.5 || Math.abs(clamped.y - now.y) > 0.5) this.moveTo(clamped);
            });
        });
        // Refit on every change of size (ISC-407): the box just changed by the whole window. Two
        // frames on, after the new box has been laid out and measured.
        let wasWide: boolean | null = null;
        effect((onCleanup) => {
            const now = this.wide();
            const before = wasWide;
            wasWide = now;
            if (before === null || now === before) return;
            let frame = requestAnimationFrame(() => (frame = requestAnimationFrame(() => this.fit())));
            onCleanup(() => cancelAnimationFrame(frame));
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

    /** The stages that open anything, by server id: the ones the expand-all control acts on (ISC-408). */
    private readonly openable = computed((): readonly string[] =>
        this.workflow()
            .phases.flatMap((phase) => phase.stages)
            .filter((stage) => opensSubNodes(stage, this.rules()))
            .map((stage) => stage.id),
    );

    /** Every stage that opens anything is open, so the control's next act is to collapse them all. */
    protected readonly allExpanded = computed(() => {
        const open = this.expanded();
        const openable = this.openable();
        return openable.length > 0 && openable.every((id) => open.has(id));
    });

    /**
     * Opens every stage that has sub-nodes, or, when all of them already are, closes them all. The
     * viewport stays where it is, as it does for one stage's own toggle.
     */
    toggleAll(): void {
        this.expanded.set(this.allExpanded() ? new Set<string>() : new Set(this.openable()));
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
        const {x, y, zoom} = this.current();
        // Not contained: getting the node out from under the sheet may push a fitted graph partly
        // out of the box. The margin still holds.
        this.moveTo({zoom, x: x + dx, y: y + dy}, false);
    }

    /**
     * Every wheel over the box, taken in the capture phase before d3-zoom's own listener on the
     * svg sees it, and never passed on: the page does not scroll, and d3's zoom-per-notch never
     * runs. A plain wheel or a two-finger scroll pans by its deltas at the current zoom; Ctrl or
     * Cmd with the wheel — and a trackpad pinch, which the browser sends as a Ctrl wheel — zooms
     * about the pointer by `exp(-deltaY * WHEEL_ZOOM_RATE)`, one step clamped to `WHEEL_ZOOM_STEP`.
     */
    private onWheel(event: WheelEvent): void {
        event.preventDefault();
        event.stopPropagation();
        const box = this.wheelBox().nativeElement;
        const unit = event.deltaMode === WheelEvent.DOM_DELTA_LINE ? WHEEL_LINE_PX : event.deltaMode === WheelEvent.DOM_DELTA_PAGE ? box.clientHeight : 1;
        const deltaX = event.deltaX * unit;
        const deltaY = event.deltaY * unit;
        const {x, y, zoom} = this.current();
        if (event.ctrlKey || event.metaKey) {
            const factor = Math.min(1 + WHEEL_ZOOM_STEP, Math.max(1 - WHEEL_ZOOM_STEP, Math.exp(-deltaY * WHEEL_ZOOM_RATE)));
            const next = Math.min(FLOW_ZOOM.max, Math.max(FLOW_ZOOM.min, zoom * factor));
            if (next === zoom) return;
            const rect = box.getBoundingClientRect();
            const px = event.clientX - rect.left;
            const py = event.clientY - rect.top;
            this.moveTo({zoom: next, x: px - ((px - x) * next) / zoom, y: py - ((py - y) * next) / zoom});
            return;
        }
        // Shift turns a vertical-only wheel sideways, where the browser has not done so already.
        const sideways = event.shiftKey && deltaX === 0;
        const dx = sideways ? deltaY : deltaX;
        const dy = sideways ? 0 : deltaY;
        if (dx === 0 && dy === 0) return;
        this.moveTo({zoom, x: x - dx, y: y - dy});
    }

    /** The viewport as last set: the one set here until the library has caught up, then the published one. */
    private current(): ViewportState {
        const published = this.vflow().viewport();
        const pending = this.pending;
        if (pending === null) return published;
        const caughtUp =
            Math.abs(published.x - pending.x) < 0.5 &&
            Math.abs(published.y - pending.y) < 0.5 &&
            Math.abs(published.zoom - pending.zoom) < 1e-4;
        if (caughtUp) this.pending = null;
        return caughtUp ? published : pending;
    }

    /**
     * Sets the viewport. The library publishes it a frame or more later, so anything moving it again
     * before then (rapid clicks, a wheel run, a swipe's momentum tail) builds on the one set here
     * rather than on the stale published one.
     */
    private moveTo(wanted: ViewportState, contain = true): void {
        const target = this.clamp(wanted, this.current(), contain);
        this.pending = target;
        if (this.pendingTimer !== null) clearTimeout(this.pendingTimer);
        this.pendingTimer = setTimeout(() => ((this.pending = null), (this.pendingTimer = null)), PENDING_MS);
        this.vflow().viewportTo(target);
    }

    /**
     * The viewport moved as little as possible so the graph stays in reach (ISC-409): a graph that
     * fits in the box stays wholly inside it when `contain` holds, and any graph keeps at least
     * `FLOW_PAN_MARGIN` px on each axis inside the box. Measured against the layout's node boxes,
     * sub-nodes included. Never further out than `from`: a reveal may leave the fitted graph
     * partly outside, and the next gesture must be free to bring it back rather than jump.
     * Unclamped where the box has no size (jsdom) or there is no graph.
     *
     * <p>"The box" is the part of it the occluder leaves free, read at every call (operator,
     * 2026-09-30). The sheet lies over the canvas rather than beside it, so bounds measured against
     * the whole box let a fitted graph count as contained while its right end sat under the sheet,
     * out of reach until the sheet was closed. Read live, the bounds follow the sheet as it opens
     * and closes, with nothing to recompute on either.
     */
    private clamp(next: ViewportState, from: ViewportState | null, contain: boolean): ViewportState {
        const nodes = this.layout().nodes;
        const box = this.wheelBox().nativeElement;
        if (nodes.length === 0 || box.clientWidth === 0 || box.clientHeight === 0) return next;
        // The client box, inside the border: the viewport's origin is there, not on the border's edge.
        const outer = box.getBoundingClientRect();
        const rect = new DOMRect(outer.left + box.clientLeft, outer.top + box.clientTop, box.clientWidth, box.clientHeight);
        const free = freeArea(rect, this.occluder()?.getBoundingClientRect() ?? null, 4 * FLOW_PAN_MARGIN);
        const zoom = next.zoom;
        const axis = (pos: number, before: number | null, lo: number, hi: number, near: number, far: number): number => {
            const start = lo * zoom;
            const end = hi * zoom;
            let min: number;
            let max: number;
            if (contain && end - start <= far - near) {
                min = near - start;
                max = far - end;
            } else {
                const keep = Math.min(FLOW_PAN_MARGIN, end - start);
                min = near + keep - end;
                max = far - keep - start;
            }
            if (before !== null) {
                min = Math.min(min, before);
                max = Math.max(max, before);
            }
            return Math.min(max, Math.max(min, pos));
        };
        const sameZoom = from !== null && Math.abs(from.zoom - zoom) < 1e-4;
        const x0 = Math.min(...nodes.map((n) => n.x));
        const x1 = Math.max(...nodes.map((n) => n.x + n.width));
        const y0 = Math.min(...nodes.map((n) => n.y));
        const y1 = Math.max(...nodes.map((n) => n.y + n.height));
        return {
            zoom,
            x: axis(next.x, sameZoom ? from.x : null, x0, x1, free.left, free.right),
            y: axis(next.y, sameZoom ? from.y : null, y0, y1, free.top, free.bottom),
        };
    }

    /** Zooms about the centre of the box, not the graph's origin, clamped to the zoom range. */
    private zoomBy(factor: number): void {
        const {x, y, zoom} = this.current();
        const next = Math.min(FLOW_ZOOM.max, Math.max(FLOW_ZOOM.min, zoom * factor));
        const host = this.box.nativeElement;
        const cx = host.clientWidth / 2;
        const cy = host.clientHeight / 2;
        this.moveTo({zoom: next, x: cx - ((cx - x) / zoom) * next, y: cy - ((cy - y) / zoom) * next});
    }

    private dataOf(
        node: LayoutNode,
        stage: WorkflowStage,
        phaseId: string,
        handles: readonly FlowHandle[],
        state: StageRunState | null,
        elapsed: number | null,
    ): FlowNodeData {
        const box = {id: node.id, width: node.width, height: node.height, handles};
        if (node.kind === 'stage') return {...box, kind: 'stage', stage, phaseId, state, elapsed: state === 'running' ? elapsed : null};
        // A sub-node links to its stage's sheet at the section its id names (ISC-406).
        const sub = {...box, kind: 'sub' as const, stageId: stage.id, icon: subIcon(node.id)};
        switch (node.kind) {
            case 'knockout': {
                const id = node.id.replace(/^knockout:/, '');
                const text = stage.knockouts?.find((knockout) => knockout.id === id)?.description ?? id;
                return {...sub, text, key: null};
            }
            case 'score-block': {
                const block = node.id.replace(/^score:/, '');
                const key = SCORE_BLOCK_KEYS[block] ?? null;
                return {...sub, text: key === null ? block : null, key};
            }
            case 'prompt':
                return {...sub, text: null, key: `rules.prompt.${stage.promptId ?? ''}`};
        }
    }
}
