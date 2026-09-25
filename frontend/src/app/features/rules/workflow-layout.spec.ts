import {WorkflowStage, WorkflowView} from '@core/model/workflow';
import {layoutWorkflow, WorkflowLayoutSizes} from './workflow-layout';

function stage(id: string, sourceId: string | null = null): WorkflowStage {
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
    };
}

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
        {id: 'sort', stages: [stage('DEDUPE'), stage('FILTER'), stage('ARCHIVE')]},
        {id: 'understand', stages: [stage('ENRICH'), stage('CONTENT'), stage('FIELDS')]},
        {id: 'judge', stages: [stage('SCORE'), stage('RETRIEVAL')]},
        {id: 'hand', stages: [stage('OPEN'), stage('PACKAGE'), stage('DIGEST')]},
    ],
    unread: [{key: 'legacy.flag', value: 'true', file: 'pipeline.yaml'}],
};

const SIZES: WorkflowLayoutSizes = {stage: {width: 180, height: 72}};

const SOURCES = ['INGEST zeta', 'INGEST alpha', 'INGEST mail', 'INGEST inbox'];
const STAGES = ['DEDUPE', 'FILTER', 'ARCHIVE', 'ENRICH', 'CONTENT', 'FIELDS', 'SCORE', 'RETRIEVAL', 'OPEN', 'PACKAGE', 'DIGEST'];

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

    it('lays every stage out once, x strictly increasing in the server order', () => {
        expect(layout.nodes).toHaveLength(SOURCES.length + STAGES.length);
        const xs = STAGES.map((id) => node(id).x);
        for (let i = 1; i < xs.length; i++) expect(xs[i]).toBeGreaterThan(xs[i - 1] ?? Number.POSITIVE_INFINITY);
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
