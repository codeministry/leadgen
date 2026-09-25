import {TestBed} from '@angular/core/testing';
import {provideRouter} from '@angular/router';
import {WorkflowStage, WorkflowView} from '@core/model/workflow';
import {layoutWorkflow} from '../workflow-layout';
import {FLOW_NODE_SIZES, FlowCanvas} from './flow-canvas';

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
    unread: [],
};

const SOURCES = ['zeta', 'alpha', 'mail', 'inbox'];
const STAGES = ['DEDUPE', 'FILTER', 'ARCHIVE', 'ENRICH', 'CONTENT', 'FIELDS', 'SCORE', 'RETRIEVAL', 'OPEN', 'PACKAGE', 'DIGEST'];

/** A rendered node: its label, and the x/y ngx-vflow placed its `<g>` at (`translate(x, y)`). */
interface Rendered {
    readonly label: string;
    readonly x: number;
    readonly y: number;
}

function rendered(host: HTMLElement): Rendered[] {
    return Array.from(host.querySelectorAll<SVGGElement>('g.vflow-node')).map((g) => {
        const match = /translate\(\s*([-\d.e]+)[ ,]+([-\d.e]+)\s*\)/.exec(g.getAttribute('transform') ?? '');
        if (match === null) throw new Error(`node without a translate: ${g.outerHTML.slice(0, 120)}`);
        return {label: g.querySelector('.flow-node')?.textContent?.trim() ?? '', x: Number(match[1]), y: Number(match[2])};
    });
}

describe('FlowCanvas', () => {
    async function render(): Promise<HTMLElement> {
        TestBed.configureTestingModule({imports: [FlowCanvas], providers: [provideRouter([])]});
        const fixture = TestBed.createComponent(FlowCanvas);
        fixture.componentRef.setInput('workflow', WORKFLOW);
        await fixture.whenStable();
        return fixture.nativeElement as HTMLElement;
    }

    const layout = layoutWorkflow(WORKFLOW, new Set<string>(), FLOW_NODE_SIZES);

    it('renders one node per layout node, labelled by the source id or the stage id', async () => {
        const nodes = rendered(await render());
        expect(nodes).toHaveLength(layout.nodes.length);
        expect(nodes.map((node) => node.label).sort()).toEqual([...SOURCES, ...STAGES].sort());
    });

    it('places every node where the layout put it, the stages in the layout x order', async () => {
        const nodes = new Map(rendered(await render()).map((node) => [node.label, node]));
        for (const node of layout.nodes) {
            const label = node.id.replace(/^(ingest|stage):/, '');
            expect(nodes.get(label)).toEqual({label, x: node.x, y: node.y});
        }
        const xs = STAGES.map((id) => nodes.get(id)?.x ?? Number.NaN);
        for (let i = 1; i < xs.length; i++) expect(xs[i]).toBeGreaterThan(xs[i - 1] ?? Number.POSITIVE_INFINITY);
        const dedupe = nodes.get('DEDUPE')?.x ?? Number.NaN;
        for (const source of SOURCES) expect(nodes.get(source)?.x).toBeLessThan(dedupe);
    });

    it('draws one edge per layout edge', async () => {
        const host = await render();
        expect(host.querySelectorAll('g[edge]').length).toBe(layout.edges.length);
    });
});
