import {RulesView} from '@core/model/rules-view';
import {WorkflowKnockout, WorkflowStage, WorkflowView} from '@core/model/workflow';
import {layoutWorkflow, opensSubNodes, SUB_RAIL_X, WorkflowLayoutSizes} from './workflow-layout';

function stage(id: string, sourceId: string | null = null, extra: Partial<WorkflowStage> = {}): WorkflowStage {
    return {
        id,
        kind: sourceId === null ? 'stage' : 'ingest',
        sourceId,
        description: `What ${id} does.`,
        costClasses: ['free'],
        promptId: null,
        settings: [],
        knockouts: null,
        width: null,
        ...extra,
    };
}

/** The server's `FilterStage` order — deliberately not alphabetical, so a sort would show. */
const KNOCKOUT_IDS = ['abroad', 'remote-share', 'out-of-reach', 'role-or-stack', 'no-core-skill', 'contract-form'];
const KNOCKOUTS: WorkflowKnockout[] = KNOCKOUT_IDS.map((id) => ({id, description: `Drops ${id}.`, keys: []}));

/** The live `/api/v1/workflow` shape: five phases, four sources deliberately not alphabetical. */
const WORKFLOW: WorkflowView = {
    phases: [
        {
            id: 'read',
            stages: [
                stage('INGEST zeta', 'zeta'),
                stage('INGEST alpha', 'alpha'),
                stage('INGEST mail', 'mail'),
                stage('INGEST inbox', 'inbox'),
            ],
        },
        {id: 'sort', stages: [stage('DEDUPE'), stage('FILTER', null, {knockouts: KNOCKOUTS}), stage('ARCHIVE')]},
        {id: 'understand', stages: [stage('ENRICH'), stage('CONTENT', null, {promptId: 'content-classifier'}), stage('FIELDS')]},
        {id: 'judge', stages: [stage('SCORE'), stage('RETRIEVAL')]},
        {id: 'hand', stages: [stage('OPEN'), stage('PACKAGE'), stage('DIGEST')]},
    ],
    unread: [{key: 'legacy.flag', value: 'true', file: 'pipeline.yaml'}],
};

const SIZES: WorkflowLayoutSizes = {stage: {width: 180, height: 72}, sub: {width: 140, height: 40}};

const SOURCES = ['INGEST zeta', 'INGEST alpha', 'INGEST mail', 'INGEST inbox'];
const STAGES = ['DEDUPE', 'FILTER', 'ARCHIVE', 'ENRICH', 'CONTENT', 'FIELDS', 'SCORE', 'RETRIEVAL', 'OPEN', 'PACKAGE', 'DIGEST'];
/** The stage ids per phase, in the server's order — the columns the layout draws. */
const PHASES = WORKFLOW.phases.map((phase) => phase.stages.map((s) => s.id));

type Layout = ReturnType<typeof layoutWorkflow>;

function stageNodeOf(layout: Layout, stageId: string) {
    const found = layout.nodes.find((n) => n.kind === 'stage' && n.stageId === stageId);
    if (found === undefined) throw new Error(`no stage node for ${stageId}`);
    return found;
}

/**
 * Phase columns: every stage of one phase shares its column's x, the columns stand left to right
 * in the server's phase order, and within a phase y strictly increases in the server's order.
 */
function expectPhaseColumns(layout: Layout) {
    const columnXs = PHASES.map((ids) => {
        const xs = ids.map((id) => stageNodeOf(layout, id).x);
        for (const x of xs) expect(x).toBe(xs[0]);
        const ys = ids.map((id) => stageNodeOf(layout, id).y);
        for (let i = 1; i < ys.length; i++) expect(ys[i]).toBeGreaterThan(ys[i - 1] ?? Number.POSITIVE_INFINITY);
        return xs[0] ?? Number.NaN;
    });
    for (let i = 1; i < columnXs.length; i++) {
        expect(columnXs[i]).toBeGreaterThan(columnXs[i - 1] ?? Number.POSITIVE_INFINITY);
    }
    // the gap leaves room for the edge from one column's last stage into the next column's first
    for (let i = 1; i < PHASES.length; i++) {
        const left = stageNodeOf(layout, PHASES[i - 1]?.[0] ?? '');
        const right = stageNodeOf(layout, PHASES[i]?.[0] ?? '');
        expect(right.x - (left.x + left.width)).toBeGreaterThanOrEqual(48);
    }
}

describe('layoutWorkflow', () => {
    const layout = layoutWorkflow(WORKFLOW, new Set<string>(), SIZES);
    const byStage = new Map(layout.nodes.map((node) => [node.stageId, node]));

    function node(stageId: string) {
        const found = byStage.get(stageId);
        if (found === undefined) throw new Error(`no node for ${stageId}`);
        return found;
    }

    it('draws every ingest source as its own node with exactly one edge into DEDUPE', () => {
        const dedupe = node('DEDUPE');
        for (const source of SOURCES) {
            const from = node(source);
            const out = layout.edges.filter((edge) => edge.source === from.id);
            expect(out).toHaveLength(1);
            expect(out[0]?.target).toBe(dedupe.id);
            expect(from.x).toBeLessThan(dedupe.x);
        }
        expect(layout.edges.filter((edge) => edge.target === dedupe.id)).toHaveLength(SOURCES.length);
    });

    it('stacks the sources top to bottom in the server order', () => {
        const ys = SOURCES.map((id) => node(id).y);
        for (let i = 1; i < ys.length; i++) expect(ys[i]).toBeGreaterThan(ys[i - 1] ?? Number.POSITIVE_INFINITY);
    });

    it('lays every stage out once, in one column per phase in the server order', () => {
        expect(layout.nodes).toHaveLength(SOURCES.length + STAGES.length);
        expectPhaseColumns(layout);
    });

    it('top-aligns the columns: the first stage of every phase sits at the same y', () => {
        const tops = PHASES.map((ids) => node(ids[0] ?? '').y);
        for (const y of tops) expect(y).toBe(tops[0]);
    });

    it('joins consecutive stages by exactly one edge and adds no other', () => {
        for (let i = 1; i < STAGES.length; i++) {
            const from = node(STAGES[i - 1] ?? '');
            const to = node(STAGES[i] ?? '');
            expect(layout.edges.filter((edge) => edge.source === from.id && edge.target === to.id)).toHaveLength(1);
        }
        expect(layout.edges).toHaveLength(SOURCES.length + STAGES.length - 1);
        expect(new Set(layout.edges.map((edge) => edge.id)).size).toBe(layout.edges.length);
    });

    it('keeps "read by nothing" out of the flow', () => {
        const ids = layout.nodes.flatMap((n) => [n.id, n.stageId]);
        expect(ids.some((id) => id.includes('legacy.flag') || id.includes('unread'))).toBe(false);
    });

    it('sizes every node from the sizes argument and derives stable ids from the server ids', () => {
        for (const n of layout.nodes) {
            expect(n.width).toBe(SIZES.stage.width);
            expect(n.height).toBe(SIZES.stage.height);
        }
        const again = layoutWorkflow(WORKFLOW, new Set<string>(), SIZES);
        expect(again.nodes.map((n) => n.id)).toEqual(layout.nodes.map((n) => n.id));
        expect(new Set(layout.nodes.map((n) => n.id)).size).toBe(layout.nodes.length);
        expect(node('INGEST zeta').id).toContain('zeta');
        expect(node('DEDUPE').id).toContain('DEDUPE');
    });
});

/** A rule set with something in every SCORE block. */
const RULES: RulesView = {
    version: 'v1',
    weights: [{key: 'java', points: 10}],
    penalties: [{key: 'onsite', points: -5}],
    thresholds: {autoShortlist: 70, review: 50, discard: 30},
    archiveAfterDays: 30,
    knockouts: [],
    interestTopics: [{name: 'payments', weight: 5}],
    disinterestTopics: [],
};

describe('layoutWorkflow with expanded stages', () => {
    const collapsed = layoutWorkflow(WORKFLOW, new Set<string>(), SIZES, RULES);

    function subNodes(layout: ReturnType<typeof layoutWorkflow>, parent: string) {
        return layout.nodes.filter((n) => n.parent === parent);
    }

    const stageNode = stageNodeOf;

    /** No two boxes share a pixel. */
    function expectNoOverlap(layout: Layout) {
        for (const a of layout.nodes) {
            for (const b of layout.nodes) {
                if (a === b) continue;
                const apart = a.x + a.width <= b.x || b.x + b.width <= a.x || a.y + a.height <= b.y || b.y + b.height <= a.y;
                expect(apart, `${a.id} overlaps ${b.id}`).toBe(true);
            }
        }
    }

    /**
     * Run order holds: the phase columns and their x are those of the collapsed layout, and an
     * opened stage's sub-nodes stack directly below it inside its column, above the next stage.
     */
    function expectRunOrder(layout: Layout) {
        expectPhaseColumns(layout);
        expectNoOverlap(layout);
        for (const id of [...SOURCES, ...STAGES]) expect(stageNode(layout, id).x).toBe(stageNode(collapsed, id).x);
        PHASES.forEach((ids, column) => {
            const nextColumn = PHASES[column + 1]?.[0];
            ids.forEach((id, i) => {
                const parent = stageNode(layout, id);
                let bottom = parent.y + parent.height;
                for (const sub of subNodes(layout, parent.id)) {
                    expect(sub.y).toBeGreaterThanOrEqual(bottom);
                    expect(sub.x).toBeGreaterThanOrEqual(parent.x);
                    if (nextColumn !== undefined) expect(sub.x + sub.width).toBeLessThan(stageNode(layout, nextColumn).x);
                    bottom = sub.y + sub.height;
                }
                const next = ids[i + 1];
                if (next !== undefined) expect(stageNode(layout, next).y).toBeGreaterThanOrEqual(bottom);
            });
        });
    }

    function expectEdgesFromParent(layout: ReturnType<typeof layoutWorkflow>, parent: string) {
        for (const sub of subNodes(layout, parent)) {
            const into = layout.edges.filter((e) => e.target === sub.id);
            expect(into).toHaveLength(1);
            expect(into[0]?.source).toBe(parent);
        }
    }

    it('expands FILTER into one knockout per stage.knockouts entry, in the server order', () => {
        const layout = layoutWorkflow(WORKFLOW, new Set(['FILTER']), SIZES, RULES);
        const filter = stageNode(layout, 'FILTER');
        const subs = subNodes(layout, filter.id);
        expect(subs.map((n) => n.id)).toEqual(KNOCKOUT_IDS.map((id) => `knockout:${id}`));
        expect(subs.every((n) => n.kind === 'knockout' && n.stageId === 'FILTER')).toBe(true);
        expect(subs.every((n) => n.width === SIZES.sub.width && n.height === SIZES.sub.height)).toBe(true);
        const ys = subs.map((n) => n.y);
        for (let i = 1; i < ys.length; i++) expect(ys[i]).toBeGreaterThan(ys[i - 1] ?? Number.POSITIVE_INFINITY);
        expectEdgesFromParent(layout, filter.id);
        expect(layout.edges).toHaveLength(collapsed.edges.length + KNOCKOUT_IDS.length);
        expectRunOrder(layout);
    });

    it('expands SCORE into weights, penalties, bands and topics from the rule set', () => {
        const layout = layoutWorkflow(WORKFLOW, new Set(['SCORE']), SIZES, RULES);
        const score = stageNode(layout, 'SCORE');
        const subs = subNodes(layout, score.id);
        expect(subs.map((n) => n.id)).toEqual(['score:weights', 'score:penalties', 'score:bands', 'score:topics']);
        expect(subs.every((n) => n.kind === 'score-block')).toBe(true);
        expectEdgesFromParent(layout, score.id);
        expectRunOrder(layout);
    });

    it('draws no SCORE block that has nothing in it, and none without a rule set', () => {
        const sparse: RulesView = {...RULES, penalties: [], interestTopics: [], disinterestTopics: []};
        const layout = layoutWorkflow(WORKFLOW, new Set(['SCORE']), SIZES, sparse);
        expect(subNodes(layout, stageNode(layout, 'SCORE').id).map((n) => n.id)).toEqual(['score:weights', 'score:bands']);
        for (const rules of [undefined, null]) {
            const bare = layoutWorkflow(WORKFLOW, new Set(['SCORE']), SIZES, rules);
            expect(subNodes(bare, stageNode(bare, 'SCORE').id)).toHaveLength(0);
        }
    });

    it('expands CONTENT into its prompt', () => {
        const layout = layoutWorkflow(WORKFLOW, new Set(['CONTENT']), SIZES, RULES);
        const content = stageNode(layout, 'CONTENT');
        const subs = subNodes(layout, content.id);
        expect(subs.map((n) => [n.id, n.kind])).toEqual([['prompt:content-classifier', 'prompt']]);
        expectEdgesFromParent(layout, content.id);
        expectRunOrder(layout);
    });

    it('expands all three at once and keeps the run order', () => {
        const layout = layoutWorkflow(WORKFLOW, new Set(['FILTER', 'SCORE', 'CONTENT']), SIZES, RULES);
        expect(layout.nodes).toHaveLength(SOURCES.length + STAGES.length + KNOCKOUT_IDS.length + 4 + 1);
        expect(new Set(layout.nodes.map((n) => n.id)).size).toBe(layout.nodes.length);
        expectRunOrder(layout);
    });

    it('lays out the collapsed graph without an overlap', () => {
        expectNoOverlap(collapsed);
    });

    it('collapsing returns exactly the collapsed layout', () => {
        layoutWorkflow(WORKFLOW, new Set(['FILTER', 'SCORE', 'CONTENT']), SIZES, RULES);
        const again = layoutWorkflow(WORKFLOW, new Set<string>(), SIZES, RULES);
        expect(again).toEqual(collapsed);
        expect(again).toEqual(layoutWorkflow(WORKFLOW, new Set<string>(), SIZES));
        expect(again.nodes.every((n) => n.kind === 'stage' && n.parent === null)).toBe(true);
    });

    it('expands nothing on a stage that has nothing to expand', () => {
        const layout = layoutWorkflow(WORKFLOW, new Set(['DEDUPE', 'ARCHIVE']), SIZES, RULES);
        expect(layout).toEqual(collapsed);
    });
});

describe('layoutWorkflow edge sides', () => {
    /** The phase index of a stage id, so an edge's kind can be checked against the columns. */
    const phaseOf = new Map(WORKFLOW.phases.flatMap((phase, i) => phase.stages.map((s) => [s.id, i] as const)));

    function edgesOf(expanded: ReadonlySet<string>) {
        const layout = layoutWorkflow(WORKFLOW, expanded, SIZES);
        const byId = new Map(layout.nodes.map((node) => [node.id, node]));
        return layout.edges.map((edge) => {
            const source = byId.get(edge.source);
            const target = byId.get(edge.target);
            if (source === undefined || target === undefined) throw new Error(`dangling edge ${edge.id}`);
            return {edge, source, target};
        });
    }

    it('joins two stages of one phase top to bottom: out of the upper bottom, into the lower top', () => {
        const within = edgesOf(new Set<string>())
            .filter(({source, target}) => source.kind === 'stage' && target.kind === 'stage')
            .filter(({source, target}) => phaseOf.get(source.stageId) === phaseOf.get(target.stageId));
        // DEDUPE→FILTER→ARCHIVE, ENRICH→CONTENT→FIELDS, SCORE→RETRIEVAL, OPEN→PACKAGE→DIGEST
        expect(within).toHaveLength(7);
        for (const {edge, source, target} of within) {
            expect(edge).toMatchObject({kind: 'within', sourceSide: 'bottom', targetSide: 'top'});
            expect(target.x).toBe(source.x);
            expect(target.y).toBeGreaterThan(source.y);
        }
    });

    it('crosses from one phase into the next, and from every source into DEDUPE, left to right', () => {
        const across = edgesOf(new Set<string>()).filter(
            ({source, target}) => phaseOf.get(source.stageId) !== phaseOf.get(target.stageId),
        );
        // four sources into DEDUPE, then ARCHIVE→ENRICH, FIELDS→SCORE, RETRIEVAL→OPEN
        expect(across).toHaveLength(SOURCES.length + 3);
        for (const {edge, source, target} of across) {
            expect(edge).toMatchObject({kind: 'across', sourceSide: 'right', targetSide: 'left'});
            expect(target.x).toBeGreaterThan(source.x);
        }
    });

    it("hangs every sub-node off the bottom of its stage and into the sub-node's left side", () => {
        const subs = edgesOf(new Set(['FILTER', 'CONTENT'])).filter(({target}) => target.kind !== 'stage');
        expect(subs).toHaveLength(KNOCKOUT_IDS.length + 1);
        for (const {edge, source, target} of subs) {
            expect(edge).toMatchObject({kind: 'sub', sourceSide: 'bottom', targetSide: 'left'});
            expect(target.parent).toBe(source.id);
            expect(target.y).toBeGreaterThan(source.y);
            // the rail runs down the indent, left of the sub-node's box
            expect(source.x + SUB_RAIL_X).toBeLessThan(target.x);
        }
    });

    it('keeps the within-phase edges vertical while stages are expanded', () => {
        const within = edgesOf(new Set(['FILTER', 'CONTENT'])).filter(({edge}) => edge.kind === 'within');
        expect(within).toHaveLength(7);
        for (const {edge, source, target} of within) {
            expect(edge).toMatchObject({sourceSide: 'bottom', targetSide: 'top'});
            expect(target.x).toBe(source.x);
        }
    });
});

describe('layoutWorkflow with the prompts the server sends', () => {
    /** The live wire: SCORE sends `scoring`, and a source whose extraction asks a model sends `extraction`. */
    const LIVE: WorkflowView = {
        ...WORKFLOW,
        phases: WORKFLOW.phases.map((phase) => ({
            ...phase,
            stages: phase.stages.map((s) => {
                if (s.id === 'SCORE') return {...s, promptId: 'scoring'};
                if (s.id === 'INGEST inbox') return {...s, promptId: 'extraction'};
                return s;
            }),
        })),
    };
    const all = LIVE.phases.flatMap((phase) => phase.stages);
    const byId = (id: string) => {
        const found = all.find((s) => s.id === id);
        if (found === undefined) throw new Error(`no stage ${id}`);
        return found;
    };

    it('opens SCORE into its four blocks and then its prompt, and into the prompt alone without a rule set', () => {
        const withRules = layoutWorkflow(LIVE, new Set(['SCORE']), SIZES, RULES);
        const without = layoutWorkflow(LIVE, new Set(['SCORE']), SIZES, null);
        const subsOf = (layout: ReturnType<typeof layoutWorkflow>) =>
            layout.nodes.filter((n) => n.parent === 'stage:SCORE').map((n) => n.id);

        expect(subsOf(withRules)).toEqual(['score:weights', 'score:penalties', 'score:bands', 'score:topics', 'prompt:scoring']);
        expect(subsOf(without)).toEqual(['prompt:scoring']);
    });

    it('opens a source that asks a model into its prompt, and offers no toggle on one that does not', () => {
        const layout = layoutWorkflow(LIVE, new Set(['INGEST inbox']), SIZES, RULES);
        const inbox = layout.nodes.find((n) => n.stageId === 'INGEST inbox' && n.kind === 'stage');

        expect(layout.nodes.filter((n) => n.parent === inbox?.id).map((n) => n.id)).toEqual(['prompt:extraction']);
        expect(opensSubNodes(byId('INGEST inbox'), RULES)).toBe(true);
        expect(opensSubNodes(byId('INGEST zeta'), RULES)).toBe(false);
    });
});
