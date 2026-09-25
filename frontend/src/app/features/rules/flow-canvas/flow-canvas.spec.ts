import {TestBed} from '@angular/core/testing';
import {By} from '@angular/platform-browser';
import {provideRouter} from '@angular/router';
import {VflowComponent} from 'ngx-vflow';
import {LastRunView} from '@core/model/last-run';
import {RulesView} from '@core/model/rules-view';
import {WorkflowStage, WorkflowView} from '@core/model/workflow';
import {SUB_ICONS} from '../stage-marks';
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

const SOURCE = {documents: 1, written: 0, announced: null, complete: true};

/** Every count distinct; FILTER removed 12548 so the chip's grouping is visible. */
const LAST_RUN: LastRunView = {
    finishedAt: '2026-09-24T04:13:00Z',
    status: 'COMPLETE',
    scoreModel: null,
    extracted: 101,
    written: 77,
    merged: 13,
    enriched: 45,
    removed: {abroad: 12000, rate: 548},
    filterConsidered: 12600,
    filterPassed: 52,
    scored: 29,
    shortlisted: 4,
    review: 6,
    packaged: 2,
    digestWritten: true,
    sources: [
        {...SOURCE, sourceId: 'zeta', extracted: 61},
        {...SOURCE, sourceId: 'alpha', extracted: 40},
    ],
    stages: [],
};

/** The stages `stageCounts` gives a number for under LAST_RUN: two sources and four stages. */
const COUNTED = ['INGEST zeta', 'INGEST alpha', 'FILTER', 'ENRICH', 'SCORE', 'PACKAGE'];

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
        const id = g.querySelector<HTMLElement>('[data-node]')?.dataset['node'] ?? '';
        return {label: id.replace(/^(ingest|stage):/, ''), x: Number(match[1]), y: Number(match[2])};
    });
}

/*
 * jsdom gap, for the whole file: its `SVGSVGElement` has no `width`/`height`, and the first fit's
 * d3 transition reads `svg.width.baseVal` when its timer fires — which may be after the test that
 * mounted the canvas has ended. Left in place until the file is done; the environment goes with it.
 */
const fileSvgProto = SVGSVGElement.prototype as unknown as Record<string, unknown>;
for (const side of ['width', 'height'].filter((s) => !(s in fileSvgProto))) {
    Object.defineProperty(fileSvgProto, side, {configurable: true, get: () => ({baseVal: {value: 0}})});
}

describe('FlowCanvas', () => {
    async function render(lastRun: LastRunView | null = null): Promise<HTMLElement> {
        TestBed.configureTestingModule({imports: [FlowCanvas], providers: [provideRouter([])]});
        const fixture = TestBed.createComponent(FlowCanvas);
        fixture.componentRef.setInput('workflow', WORKFLOW);
        fixture.componentRef.setInput('lastRun', lastRun);
        await fixture.whenStable();
        return fixture.nativeElement as HTMLElement;
    }

    const layout = layoutWorkflow(WORKFLOW, new Set<string>(), FLOW_NODE_SIZES);

    it('renders one node per layout node, labelled by the source id or the stage id', async () => {
        const nodes = rendered(await render());
        expect(nodes).toHaveLength(layout.nodes.length);
        expect(nodes.map((node) => node.label).sort()).toEqual([...SOURCES, ...STAGES].sort());
    });

    it('places every node where the layout put it, one column per phase', async () => {
        const nodes = new Map(rendered(await render()).map((node) => [node.label, node]));
        for (const node of layout.nodes) {
            const label = node.id.replace(/^(ingest|stage):/, '');
            expect(nodes.get(label)).toEqual({label, x: node.x, y: node.y});
        }
        const columns = WORKFLOW.phases.map((phase) => phase.stages.map((s) => s.sourceId ?? s.id));
        const columnXs = columns.map((ids) => {
            const xs = ids.map((id) => nodes.get(id)?.x ?? Number.NaN);
            for (const x of xs) expect(x).toBe(xs[0]);
            const ys = ids.map((id) => nodes.get(id)?.y ?? Number.NaN);
            for (let i = 1; i < ys.length; i++) expect(ys[i]).toBeGreaterThan(ys[i - 1] ?? Number.POSITIVE_INFINITY);
            return xs[0] ?? Number.NaN;
        });
        for (let i = 1; i < columnXs.length; i++) expect(columnXs[i]).toBeGreaterThan(columnXs[i - 1] ?? Number.POSITIVE_INFINITY);
        const dedupe = nodes.get('DEDUPE')?.x ?? Number.NaN;
        for (const source of SOURCES) expect(nodes.get(source)?.x).toBeLessThan(dedupe);
    });

    it('draws one edge per layout edge', async () => {
        const host = await render();
        expect(host.querySelectorAll('g[edge]').length).toBe(layout.edges.length);
    });

    /**
     * Every drawn edge names its two handles, and those handles sit in the source and target
     * node's own markup on exactly the sides the layout chose for that edge.
     */
    function expectEdgesOnLayoutSides(host: HTMLElement, expected: ReturnType<typeof layoutWorkflow>) {
        const paths = Array.from(host.querySelectorAll<SVGPathElement>('g[edge] path.flow-edge'));
        expect(paths).toHaveLength(expected.edges.length);
        const byId = new Map(paths.map((path) => [path.dataset['edge'] ?? '', path]));
        for (const edge of expected.edges) {
            const path = byId.get(edge.id);
            expect(path, edge.id).toBeDefined();
            const ends = [
                {node: edge.source, handle: path?.dataset['sourceHandle'], side: edge.sourceSide, type: 'source'},
                {node: edge.target, handle: path?.dataset['targetHandle'], side: edge.targetSide, type: 'target'},
            ];
            for (const end of ends) {
                const box = host.querySelector(`[data-node="${end.node}"]`);
                const handle = box?.querySelector<HTMLElement>(`handle[data-handle="${end.handle ?? ''}"]`);
                expect(handle, `${edge.id} ${end.type}`).toBeTruthy();
                expect(handle?.dataset['position'], `${edge.id} ${end.type}`).toBe(end.side);
                expect(handle?.dataset['type'], `${edge.id} ${end.type}`).toBe(end.type);
            }
        }
    }

    it('anchors every edge on the handles the layout named: vertical within a phase, horizontal across', async () => {
        const host = await render();
        expectEdgesOnLayoutSides(host, layout);
        const kinds = new Set(layout.edges.map((edge) => edge.kind));
        expect(kinds).toEqual(new Set(['within', 'across']));
    });

    it('anchors the edges into opened sub-nodes on the handles the layout named', async () => {
        TestBed.configureTestingModule({imports: [FlowCanvas], providers: [provideRouter([])]});
        const fixture = TestBed.createComponent(FlowCanvas);
        const withPrompt: WorkflowView = {
            ...WORKFLOW,
            phases: WORKFLOW.phases.map((phase) => ({
                ...phase,
                stages: phase.stages.map((s) => (s.id === 'CONTENT' ? {...s, promptId: 'content-classifier'} : s)),
            })),
        };
        const expanded = new Set(['CONTENT']);
        fixture.componentRef.setInput('workflow', withPrompt);
        fixture.componentRef.setInput('expanded', expanded);
        await fixture.whenStable();
        const opened = layoutWorkflow(withPrompt, expanded, FLOW_NODE_SIZES);
        expect(opened.edges.some((edge) => edge.kind === 'sub')).toBe(true);
        expectEdgesOnLayoutSides(fixture.nativeElement as HTMLElement, opened);
    });

    it('draws every stage node as an lg-flow-node carrying its phase', async () => {
        const host = await render();
        const cards = Array.from(host.querySelectorAll<HTMLElement>('g.vflow-node lg-flow-node'));
        expect(cards).toHaveLength(layout.nodes.length);
        const filter = host.querySelector('lg-flow-node [data-stage="FILTER"]');
        expect(filter?.textContent).toContain('Sort');
    });

    it('puts a count chip on exactly the stages the last run counted', async () => {
        const host = await render(LAST_RUN);
        const chipped = Array.from(host.querySelectorAll<HTMLElement>('lg-flow-node'))
            .filter((card) => card.querySelector('.flow-node-count') !== null)
            .map((card) => card.querySelector<HTMLElement>('[data-stage]')?.dataset['stage']);
        expect(chipped.sort()).toEqual([...COUNTED].sort());
        expect(host.querySelector('lg-flow-node [data-stage="FILTER"] .flow-node-count')?.textContent).toContain('−12,548');
    });

    it('puts no chip on any node without a last run', async () => {
        const host = await render(null);
        expect(host.querySelectorAll('lg-flow-node').length).toBe(layout.nodes.length);
        expect(host.querySelectorAll('.flow-node-count')).toHaveLength(0);
    });

    it('marks the stage the last run failed at, and only that one', async () => {
        const failedAt = {position: 3, stage: 'ENRICH', startedAt: '', endedAt: '', millis: 1, status: 'FAILED', note: null, width: null};
        const host = await render({...LAST_RUN, status: 'FAILED', stages: [failedAt]});
        const marked = Array.from(host.querySelectorAll<HTMLElement>('lg-flow-node'))
            .filter((card) => card.querySelector('.flow-node-failed') !== null)
            .map((card) => card.querySelector<HTMLElement>('[data-stage]')?.dataset['stage']);
        expect(marked).toEqual(['ENRICH']);
    });

    /** Replaces the named stages of WORKFLOW with a patched copy, keeping every phase and its order. */
    function patched(patches: Readonly<Record<string, Partial<WorkflowStage>>>): WorkflowView {
        return {
            ...WORKFLOW,
            phases: WORKFLOW.phases.map((phase) => ({
                ...phase,
                stages: phase.stages.map((s) => ({...s, ...(patches[s.id] ?? {})})),
            })),
        };
    }

    describe('width (ISC-402)', () => {
        const WIDE = patched({
            ENRICH: {width: {key: 'enrichment.fetch.concurrency', value: 4}},
            SCORE: {width: {key: 'llm.concurrency', value: 1}},
        });

        function card(host: HTMLElement, id: string): HTMLElement | null {
            return host.querySelector(`lg-flow-node [data-stage="${id}"]`);
        }

        it('stacks ENRICH at width 4 with ×4, leaves SCORE at 1 and FILTER without a width plain, and keeps one edge in and one out', async () => {
            TestBed.configureTestingModule({imports: [FlowCanvas], providers: [provideRouter([])]});
            const fixture = TestBed.createComponent(FlowCanvas);
            fixture.componentRef.setInput('workflow', WIDE);
            await fixture.whenStable();
            const host = fixture.nativeElement as HTMLElement;

            expect(card(host, 'ENRICH')?.classList).toContain('is-stacked');
            expect(host.querySelector('[data-node="stage:ENRICH"] .flow-node-width')?.textContent?.trim()).toBe('×4');
            for (const id of ['SCORE', 'FILTER']) {
                expect(card(host, id)?.classList, id).not.toContain('is-stacked');
                expect(host.querySelector(`[data-node="stage:${id}"] .flow-node-width`), id).toBeNull();
            }
            expect(host.querySelectorAll('.flow-node-width')).toHaveLength(1);

            const edges = Array.from(host.querySelectorAll<SVGPathElement>('g[edge] path.flow-edge'), (path) => path.dataset['edge'] ?? '');
            expect(edges.filter((id) => id.startsWith('stage:ENRICH->'))).toHaveLength(1);
            expect(edges.filter((id) => id.endsWith('->stage:ENRICH'))).toHaveLength(1);
            expect(edges).toHaveLength(layoutWorkflow(WIDE, new Set<string>(), FLOW_NODE_SIZES).edges.length);
        });
    });

    describe('expanding a stage (ISC-390)', () => {
        /** Six knockouts in `FilterStage` order, deliberately not alphabetical. */
        const KNOCKOUTS = ['remote', 'abroad', 'rate', 'language', 'excluded', 'duration'];
        const OPENABLE = patched({
            FILTER: {knockouts: KNOCKOUTS.map((id) => ({id, description: `Drops by ${id}.`, keys: []}))},
            CONTENT: {promptId: 'content-classifier'},
        });
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

        /**
         * jsdom gap: its `SVGSVGElement` has no `width`/`height`. ngx-vflow sends every viewport
         * change, the first fit included, through a d3 transition, and d3-zoom's default extent
         * reads `svg.width.baseVal` when that transition's timer fires — which these multi-step
         * tests live long enough to see. A zero extent is all the fit needs here.
         */
        const svgProto = SVGSVGElement.prototype as unknown as Record<string, unknown>;
        const shimmed = ['width', 'height'].filter((side) => !(side in svgProto));
        beforeAll(() => {
            for (const side of shimmed) {
                Object.defineProperty(svgProto, side, {configurable: true, get: () => ({baseVal: {value: 0}})});
            }
        });
        afterAll(() => {
            for (const side of shimmed) Reflect.deleteProperty(svgProto, side);
        });

        async function mount() {
            TestBed.configureTestingModule({imports: [FlowCanvas], providers: [provideRouter([])]});
            const fixture = TestBed.createComponent(FlowCanvas);
            fixture.componentRef.setInput('workflow', OPENABLE);
            fixture.componentRef.setInput('rules', RULES);
            await fixture.whenStable();
            const host = fixture.nativeElement as HTMLElement;
            const click = async (stageId: string) => {
                const button = host.querySelector<HTMLButtonElement>(`[data-node="stage:${stageId}"] button[data-action="expand"]`);
                expect(button, stageId).toBeTruthy();
                button?.click();
                await fixture.whenStable();
            };
            return {host, click};
        }

        /** Sub-node ids under a prefix, top to bottom. */
        function subs(host: HTMLElement, prefix: string): string[] {
            return rendered(host)
                .filter((node) => node.label.startsWith(prefix))
                .sort((a, b) => a.y - b.y)
                .map((node) => node.label.slice(prefix.length));
        }

        /** Stage nodes only, keyed by label: where each sits. */
        function stagePositions(host: HTMLElement): Map<string, Rendered> {
            const ids = new Set([...SOURCES, ...STAGES]);
            return new Map(rendered(host).filter((node) => ids.has(node.label)).map((node) => [node.label, node]));
        }

        /** Every phase column still reads top to bottom in the server's order. */
        function expectRunOrder(host: HTMLElement) {
            const at = stagePositions(host);
            for (const phase of OPENABLE.phases) {
                const ys = phase.stages.map((s) => at.get(s.sourceId ?? s.id)?.y ?? Number.NaN);
                for (let i = 1; i < ys.length; i++) expect(ys[i], phase.id).toBeGreaterThan(ys[i - 1] ?? Number.POSITIVE_INFINITY);
            }
        }

        it('offers the toggle on FILTER, SCORE and CONTENT and on no other node', async () => {
            const {host} = await mount();
            const owners = Array.from(host.querySelectorAll<HTMLElement>('button[data-action="expand"]'), (b) =>
                b.closest<HTMLElement>('[data-node]')?.dataset['node'],
            );
            expect(owners.sort()).toEqual(['stage:CONTENT', 'stage:FILTER', 'stage:SCORE']);
        });

        it('opens FILTER into its six knockouts in server order and closes them again', async () => {
            const {host, click} = await mount();
            const before = stagePositions(host);

            await click('FILTER');
            expect(subs(host, 'knockout:')).toEqual(KNOCKOUTS);
            expect(host.querySelector('[data-node="stage:FILTER"] button[data-action="expand"]')?.getAttribute('aria-expanded')).toBe('true');
            expectRunOrder(host);

            await click('FILTER');
            expect(subs(host, 'knockout:')).toEqual([]);
            expect(stagePositions(host)).toEqual(before);
        });

        it('opens SCORE into weights, penalties, bands and topics and closes them again', async () => {
            const {host, click} = await mount();
            const before = stagePositions(host);

            await click('SCORE');
            expect(subs(host, 'score:')).toEqual(['weights', 'penalties', 'bands', 'topics']);
            expectRunOrder(host);

            await click('SCORE');
            expect(subs(host, 'score:')).toEqual([]);
            expect(stagePositions(host)).toEqual(before);
        });

        it('opens CONTENT into its prompt and closes it again', async () => {
            const {host, click} = await mount();
            const before = stagePositions(host);

            await click('CONTENT');
            expect(subs(host, 'prompt:')).toEqual(['content-classifier']);
            expectRunOrder(host);

            await click('CONTENT');
            expect(subs(host, 'prompt:')).toEqual([]);
            expect(stagePositions(host)).toEqual(before);
        });

        it('keeps every stage in run order with all three open at once', async () => {
            const {host, click} = await mount();
            await click('FILTER');
            await click('SCORE');
            await click('CONTENT');
            expect(subs(host, 'knockout:')).toHaveLength(6);
            expect(subs(host, 'score:')).toHaveLength(4);
            expect(subs(host, 'prompt:')).toHaveLength(1);
            expectRunOrder(host);
        });

        it('expands every openable stage with one toolbar control and collapses them all again, keeping the viewport (ISC-408)', async () => {
            TestBed.configureTestingModule({imports: [FlowCanvas], providers: [provideRouter([])]});
            const fixture = TestBed.createComponent(FlowCanvas);
            fixture.componentRef.setInput('workflow', OPENABLE);
            fixture.componentRef.setInput('rules', RULES);
            await fixture.whenStable();
            const host = fixture.nativeElement as HTMLElement;
            const vflow = fixture.debugElement.query(By.directive(VflowComponent)).componentInstance as VflowComponent;
            const control = (): HTMLButtonElement => {
                const found = host.querySelector<HTMLButtonElement>('.flow-canvas-controls button[data-action="expand-all"]');
                if (found === null) throw new Error('no expand-all control');
                return found;
            };
            const expandedStages = (): string[] =>
                Array.from(host.querySelectorAll<HTMLElement>('button[data-action="expand"][aria-expanded="true"]'), (b) =>
                    b.closest<HTMLElement>('[data-node]')?.dataset['node'] ?? '',
                ).sort();
            // Let the library initialise and the first fit go out; what must not happen is a second one.
            for (let i = 0; i < 50 && !vflow.initialized(); i++) {
                await new Promise((resolve) => setTimeout(resolve, 10));
                await fixture.whenStable();
            }
            expect(vflow.initialized()).toBe(true);
            await fixture.whenStable();
            const moves = [vi.spyOn(vflow, 'fitView'), vi.spyOn(vflow, 'viewportTo'), vi.spyOn(vflow, 'zoomTo'), vi.spyOn(vflow, 'panTo')];
            const expandName = control().getAttribute('aria-label') ?? '';
            expect(expandName.trim()).not.toBe('');

            control().click();
            await fixture.whenStable();
            expect(expandedStages()).toEqual(['stage:CONTENT', 'stage:FILTER', 'stage:SCORE']);
            expect(subs(host, 'knockout:')).toEqual(KNOCKOUTS);
            const collapseName = control().getAttribute('aria-label') ?? '';
            expect(collapseName.trim()).not.toBe('');
            expect(collapseName).not.toBe(expandName);
            for (const move of moves) expect(move).not.toHaveBeenCalled();

            control().click();
            await fixture.whenStable();
            expect(expandedStages()).toEqual([]);
            expect(subs(host, 'knockout:')).toEqual([]);
            expect(control().getAttribute('aria-label')).toBe(expandName);
            for (const move of moves) expect(move).not.toHaveBeenCalled();
        });

        describe('a sub-node is a link into its stage\'s sheet (ISC-406)', () => {
            /** The sub-node's link, its `stage` and `section` query parameters, and the icon it shows. */
            function link(host: HTMLElement, nodeId: string): {params: URLSearchParams; icon: string | undefined; a: HTMLAnchorElement} {
                const a = host.querySelector<HTMLAnchorElement>(`[data-node="${nodeId}"] a[href]`);
                if (a === null) throw new Error(`no link on ${nodeId}`);
                const href = a.getAttribute('href') ?? '';
                return {a, params: new URLSearchParams(href.slice(href.indexOf('?') + 1)), icon: a.querySelector<HTMLElement>('[data-icon]')?.dataset['icon']};
            }

            it('links a knockout, a SCORE block and a prompt to their parent stage and their section, each with its kind\'s icon', async () => {
                const {host, click} = await mount();
                await click('FILTER');
                await click('SCORE');
                await click('CONTENT');

                const rate = link(host, 'knockout:rate');
                expect(rate.params.get('stage')).toBe('FILTER');
                expect(rate.params.get('section')).toBe('knockout:rate');
                expect(rate.icon).toBe(SUB_ICONS.knockout);

                const bands = link(host, 'score:bands');
                expect(bands.params.get('stage')).toBe('SCORE');
                expect(bands.params.get('section')).toBe('score:bands');
                expect(bands.icon).toBe(SUB_ICONS.bands);
                expect(link(host, 'score:weights').icon).toBe(SUB_ICONS.weights);
                expect(link(host, 'score:penalties').icon).toBe(SUB_ICONS.penalties);
                expect(link(host, 'score:topics').icon).toBe(SUB_ICONS.topics);

                const prompt = link(host, 'prompt:content-classifier');
                expect(prompt.params.get('stage')).toBe('CONTENT');
                expect(prompt.params.get('section')).toBe('prompt:content-classifier');
                expect(prompt.icon).toBe(SUB_ICONS.prompt);

                // six kinds, six different icons
                expect(new Set(Object.values(SUB_ICONS)).size).toBe(6);
            });

            it('toggles nothing when a sub-node is clicked', async () => {
                const {host, click} = await mount();
                await click('FILTER');
                link(host, 'knockout:rate').a.click();
                expect(subs(host, 'knockout:')).toEqual(KNOCKOUTS);
            });
        });
    });

    describe('fullscreen control (ISC-407)', () => {
        function mountPlain(fullscreen: boolean | null) {
            TestBed.configureTestingModule({imports: [FlowCanvas], providers: [provideRouter([])]});
            const fixture = TestBed.createComponent(FlowCanvas);
            fixture.componentRef.setInput('workflow', WORKFLOW);
            fixture.componentRef.setInput('fullscreen', fullscreen);
            fixture.detectChanges();
            return {fixture, host: fixture.nativeElement as HTMLElement};
        }

        it('offers a named toggle beside the zoom controls with its state in aria-pressed', () => {
            const {fixture, host} = mountPlain(false);
            const button = host.querySelector<HTMLButtonElement>('.flow-canvas-controls button[data-action="fullscreen"]');
            expect(button).not.toBeNull();
            expect((button?.getAttribute('aria-label') ?? '').trim().length).toBeGreaterThan(0);
            expect(button?.getAttribute('aria-pressed')).toBe('false');

            let asked = 0;
            fixture.componentInstance.fullscreenToggle.subscribe(() => asked++);
            button?.click();
            expect(asked).toBe(1);

            fixture.componentRef.setInput('fullscreen', true);
            fixture.detectChanges();
            expect(button?.getAttribute('aria-pressed')).toBe('true');
        });

        it('hides the control where the browser has no Fullscreen API', () => {
            const {host} = mountPlain(null);
            expect(host.querySelector('button[data-action="fullscreen"]')).toBeNull();
        });
    });
});
