import {ComponentFixture, TestBed} from '@angular/core/testing';
import {provideRouter} from '@angular/router';
import {page} from 'vitest/browser';
import {WorkflowStage, WorkflowView} from '@core/model/workflow';
import {contrastRatio, Rgb} from '@core/theme/color-math';
import {FLOW_PAN_MARGIN, FlowCanvas} from './flow-canvas';

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

/** The live shape: five phases, four sources, eleven stages — wider than the box at zoom 1. */
const WORKFLOW: WorkflowView = {
    phases: [
        {
            id: 'read',
            stages: [stage('INGEST zeta', 'zeta'), stage('INGEST alpha', 'alpha'), stage('INGEST mail', 'mail'), stage('INGEST inbox', 'inbox')],
        },
        {id: 'sort', stages: [stage('DEDUPE'), stage('FILTER'), stage('ARCHIVE')]},
        {id: 'understand', stages: [stage('ENRICH'), stage('CONTENT'), stage('FIELDS')]},
        {id: 'judge', stages: [stage('SCORE'), stage('RETRIEVAL')]},
        {id: 'hand', stages: [stage('OPEN'), stage('PACKAGE'), stage('DIGEST')]},
    ],
    unread: [],
};

const NODE_COUNT = 15;

function frames(count = 2): Promise<void> {
    return new Promise((resolve) => {
        const step = (left: number): void => {
            if (left === 0) resolve();
            else requestAnimationFrame(() => step(left - 1));
        };
        step(count);
    });
}

/** Mounted in a 1200 px wide column, about what the canvas gets at 1440×900 beside the nav rail. */
async function mount(workflow: WorkflowView = WORKFLOW): Promise<{fixture: ComponentFixture<FlowCanvas>; host: HTMLElement; frame: HTMLElement}> {
    TestBed.configureTestingModule({imports: [FlowCanvas], providers: [provideRouter([])]});
    const frame = document.createElement('div');
    frame.style.inlineSize = '1200px';
    document.body.appendChild(frame);
    const fixture = TestBed.createComponent(FlowCanvas);
    fixture.componentRef.setInput('workflow', workflow);
    const host = fixture.nativeElement as HTMLElement;
    frame.appendChild(host);
    await settle(fixture);
    return {fixture, host, frame};
}

async function settle(fixture: ComponentFixture<FlowCanvas>): Promise<void> {
    await fixture.whenStable();
    await frames(4);
    await fixture.whenStable();
    await frames(2);
}

function nodeBoxes(host: HTMLElement): DOMRect[] {
    return Array.from(host.querySelectorAll<HTMLElement>('lg-flow-node')).map((node) => node.getBoundingClientRect());
}

/** The part of the host the graph draws in: the host minus nothing, since the controls float over it. */
function expectAllInside(host: HTMLElement): void {
    const box = host.getBoundingClientRect();
    const nodes = nodeBoxes(host);
    expect(nodes).toHaveLength(NODE_COUNT);
    for (const node of nodes) {
        expect(node.width).toBeGreaterThan(0);
        expect(node.left).toBeGreaterThanOrEqual(box.left - 0.5);
        expect(node.right).toBeLessThanOrEqual(box.right + 0.5);
        expect(node.top).toBeGreaterThanOrEqual(box.top - 0.5);
        expect(node.bottom).toBeLessThanOrEqual(box.bottom + 0.5);
    }
}

function button(host: HTMLElement, action: 'zoom-in' | 'zoom-out' | 'fit'): HTMLButtonElement {
    const found = host.querySelector<HTMLButtonElement>(`button[data-action="${action}"]`);
    if (found === null) throw new Error(`no ${action} button`);
    return found;
}

/**
 * In headless Chromium the nodes get real boxes — the one thing jsdom cannot tell us,
 * because it lays nothing out.
 */
describe('FlowCanvas in a browser', () => {
    afterEach(() => {
        document.body.replaceChildren();
    });

    it('fits the whole graph into its box on first render, in phase columns, with edges', async () => {
        const {host} = await mount();

        expectAllInside(host);
        const ids = Array.from(host.querySelectorAll<HTMLElement>('lg-flow-node [data-stage]')).map((a) => a.dataset['stage']);
        const boxes = new Map(ids.map((id, i) => [id, nodeBoxes(host)[i]]));
        expect(boxes.get('INGEST zeta')?.top).toBeLessThan(boxes.get('INGEST alpha')?.top ?? 0);
        expect(boxes.get('INGEST zeta')?.left).toBeLessThan(boxes.get('DEDUPE')?.left ?? 0);
        // one column per phase: DEDUPE and FILTER stack, the next phase stands to the right
        expect(Math.abs((boxes.get('DEDUPE')?.left ?? 0) - (boxes.get('FILTER')?.left ?? 1))).toBeLessThan(0.5);
        expect(boxes.get('DEDUPE')?.top).toBeLessThan(boxes.get('FILTER')?.top ?? 0);
        expect(boxes.get('ARCHIVE')?.right).toBeLessThan(boxes.get('ENRICH')?.left ?? 0);
        expect(boxes.get('PACKAGE')?.top).toBeLessThan(boxes.get('DIGEST')?.top ?? 0);
        expect(host.querySelectorAll('path.flow-edge').length).toBeGreaterThan(0);
    });

    it('runs an edge straight down inside a column and left to right between columns, with no dots', async () => {
        const {host} = await mount();
        const box = (nodeId: string): DOMRect => {
            const found = host.querySelector(`[data-node="${nodeId}"]`);
            if (found === null) throw new Error(`no node ${nodeId}`);
            return found.getBoundingClientRect();
        };
        const edge = (edgeId: string): DOMRect => {
            const found = host.querySelector(`path.flow-edge[data-edge="${edgeId}"]`);
            if (found === null) throw new Error(`no edge ${edgeId}`);
            return found.getBoundingClientRect();
        };

        // within a phase: a short vertical connector in the gap between the two boxes
        const down = edge('stage:DEDUPE->stage:FILTER');
        expect(down.width).toBeLessThan(2);
        expect(down.top).toBeGreaterThanOrEqual(box('stage:DEDUPE').bottom - 0.5);
        expect(down.bottom).toBeLessThanOrEqual(box('stage:FILTER').top + 0.5);
        // across phases: out of the right side, into the next column's left side
        const across = edge('stage:ARCHIVE->stage:ENRICH');
        expect(across.left).toBeGreaterThanOrEqual(box('stage:ARCHIVE').right - 0.5);
        expect(across.right).toBeLessThanOrEqual(box('stage:ENRICH').left + 0.5);
        // the handles are anchors, not controls: no dot is drawn
        const dots = Array.from(host.querySelectorAll('circle.default-handle'));
        for (const dot of dots) expect(getComputedStyle(dot).display).toBe('none');
    });

    it('hangs FILTER\'s knockouts off one rail like a file tree: square segments, no overshoot, no arrow', async () => {
        const knockouts = ['remote', 'rate', 'language'];
        const openable: WorkflowView = {
            ...WORKFLOW,
            phases: WORKFLOW.phases.map((phase) => ({
                ...phase,
                stages: phase.stages.map((s) =>
                    s.id === 'FILTER' ? {...s, knockouts: knockouts.map((id) => ({id, description: `Drops by ${id}.`, keys: []}))} : s,
                ),
            })),
        };
        const {fixture, host} = await mount(openable);
        fixture.componentInstance.toggle('FILTER');
        await settle(fixture);

        const parent = host.querySelector('[data-node="stage:FILTER"]')!.getBoundingClientRect();
        for (const id of knockouts) {
            const path = host.querySelector<SVGPathElement>(`path.flow-edge[data-edge="stage:FILTER->knockout:${id}"]`);
            expect(path, id).not.toBeNull();
            const sub = host.querySelector(`[data-node="knockout:${id}"]`)!.getBoundingClientRect();
            // The absolute M/L/H/V commands of the path, as points on the screen.
            const matrix = path!.getScreenCTM()!;
            const tokens = (path!.getAttribute('d') ?? '').match(/[A-Za-z]|-?\d*\.?\d+(?:e-?\d+)?/g) ?? [];
            const points: DOMPoint[] = [];
            let cx = 0;
            let cy = 0;
            for (let i = 0; i < tokens.length; ) {
                const command = tokens[i++];
                const num = (): number => Number(tokens[i++]);
                if (command === 'M' || command === 'L') [cx, cy] = [num(), num()];
                else if (command === 'H') cx = num();
                else if (command === 'V') cy = num();
                else throw new Error(`${id}: ${command} is not a square segment in ${path!.getAttribute('d')}`);
                points.push(new DOMPoint(cx, cy).matrixTransform(matrix));
            }
            expect(points.length, id).toBeGreaterThanOrEqual(3);
            for (let i = 1; i < points.length; i++) {
                const dx = Math.abs(points[i]!.x - points[i - 1]!.x);
                const dy = Math.abs(points[i]!.y - points[i - 1]!.y);
                expect(Math.min(dx, dy), `${id} segment ${i} is diagonal`).toBeLessThan(0.5);
            }
            const rail = points[0]!;
            const end = points.at(-1)!;
            // leaves the parent's bottom on the rail, inside the indent left of the sub-node
            expect(Math.abs(rail.y - parent.bottom), id).toBeLessThan(1);
            expect(rail.x, id).toBeGreaterThan(parent.left);
            expect(rail.x, id).toBeLessThan(sub.left);
            // never left of the rail, and the last segment ends on the sub-node's left edge, at its middle
            for (const point of points) expect(point.x, id).toBeGreaterThanOrEqual(rail.x - 0.5);
            expect(Math.abs(end.x - sub.left), id).toBeLessThan(1);
            expect(end.y, id).toBeGreaterThan(sub.top);
            expect(end.y, id).toBeLessThan(sub.bottom);
            expect(path!.getAttribute('marker-end') ?? '', id).toBe('');
        }
        // the stage edges keep their arrowheads
        expect(host.querySelector('path.flow-edge[data-edge="stage:DEDUPE->stage:FILTER"]')?.getAttribute('marker-end') ?? '').not.toBe('');
    });

    it('keeps its box on a fixed height and never wider than its column', async () => {
        const {host, frame} = await mount();
        const box = host.getBoundingClientRect();

        expect(box.height).toBeGreaterThan(200);
        expect(box.width).toBeLessThanOrEqual(frame.getBoundingClientRect().width + 0.5);
        expect(document.documentElement.scrollWidth).toBeLessThanOrEqual(document.documentElement.clientWidth);
    });

    it('names its three controls', async () => {
        const {host} = await mount();

        for (const action of ['zoom-in', 'zoom-out', 'fit'] as const) {
            const name = button(host, action).getAttribute('aria-label') ?? '';
            expect(name.trim().length).toBeGreaterThan(0);
            expect(name).not.toContain('rules.');
        }
        expect(host.querySelector('.btn-primary')).toBeNull();
    });

    it('zooms in with its button and fit restores the whole graph', async () => {
        const {fixture, host} = await mount();
        const before = nodeBoxes(host)[0]?.width ?? 0;

        button(host, 'zoom-in').click();
        button(host, 'zoom-in').click();
        button(host, 'zoom-in').click();
        await settle(fixture);
        expect(nodeBoxes(host)[0]?.width ?? 0).toBeGreaterThan(before * 1.5);
        const box = host.getBoundingClientRect();
        expect(nodeBoxes(host).some((node) => node.left < box.left || node.right > box.right)).toBe(true);

        button(host, 'fit').click();
        await settle(fixture);
        expectAllInside(host);
        expect(Math.abs((nodeBoxes(host)[0]?.width ?? 0) - before)).toBeLessThan(1);
    });

    it('zooms out with its button', async () => {
        const {fixture, host} = await mount();
        button(host, 'zoom-in').click();
        await settle(fixture);
        const zoomed = nodeBoxes(host)[0]?.width ?? 0;

        button(host, 'zoom-out').click();
        await settle(fixture);
        expect(nodeBoxes(host)[0]?.width ?? 0).toBeLessThan(zoomed);
    });

    it('keeps a wheel inside the box, even at the zoom limit, and the page does not scroll', async () => {
        const {fixture, host} = await mount();
        const svg = host.querySelector('svg');
        if (svg === null) throw new Error('no svg');
        const scrollY = window.scrollY;

        for (let i = 0; i < 12; i++) button(host, 'zoom-in').click();
        await settle(fixture);
        const box = svg.getBoundingClientRect();
        const wheel = new WheelEvent('wheel', {
            deltaY: -120,
            clientX: box.left + box.width / 2,
            clientY: box.top + box.height / 2,
            bubbles: true,
            cancelable: true,
        });
        svg.dispatchEvent(wheel);
        await settle(fixture);

        // At the zoom limit d3-zoom returns before preventDefault, which would scroll the page.
        expect(wheel.defaultPrevented).toBe(true);
        expect(window.scrollY).toBe(scrollY);
    });

    describe('the wheel pans and only a pinch or Ctrl/Cmd zooms (ISC-404)', () => {
        function wheelOver(host: HTMLElement, init: WheelEventInit): WheelEvent {
            const svg = host.querySelector('svg');
            if (svg === null) throw new Error('no svg');
            const wheel = new WheelEvent('wheel', {bubbles: true, cancelable: true, ...init});
            svg.dispatchEvent(wheel);
            return wheel;
        }

        function centreOf(host: HTMLElement): {x: number; y: number} {
            const box = host.getBoundingClientRect();
            return {x: box.left + box.width / 2, y: box.top + box.height / 2};
        }

        /**
         * Mounted and zoomed three steps past the fit, so the graph is larger than the box: a
         * fitted graph stays wholly inside it (ISC-409), which leaves a free pan nowhere to go.
         */
        async function mountZoomed(): Promise<{fixture: ComponentFixture<FlowCanvas>; host: HTMLElement}> {
            const {fixture, host} = await mount();
            for (let i = 0; i < 3; i++) button(host, 'zoom-in').click();
            await settle(fixture);
            return {fixture, host};
        }

        it('pans by a plain vertical and horizontal wheel at an unchanged zoom, and the page stays put', async () => {
            const {fixture, host} = await mountZoomed();
            const scrollY = window.scrollY;
            const before = nodeBoxes(host)[0];
            if (before === undefined) throw new Error('no node');
            const at = centreOf(host);

            const vertical = wheelOver(host, {deltaY: 100, clientX: at.x, clientY: at.y});
            await settle(fixture);
            const afterVertical = nodeBoxes(host)[0];
            expect(vertical.defaultPrevented).toBe(true);
            expect(afterVertical?.width ?? 0).toBeCloseTo(before.width, 1);
            expect(afterVertical?.top ?? 0).toBeCloseTo(before.top - 100, 0);
            expect(afterVertical?.left ?? 0).toBeCloseTo(before.left, 0);

            const horizontal = wheelOver(host, {deltaX: 60, clientX: at.x, clientY: at.y});
            await settle(fixture);
            const afterHorizontal = nodeBoxes(host)[0];
            expect(horizontal.defaultPrevented).toBe(true);
            expect(afterHorizontal?.width ?? 0).toBeCloseTo(before.width, 1);
            expect(afterHorizontal?.left ?? 0).toBeCloseTo(before.left - 60, 0);
            expect(afterHorizontal?.top ?? 0).toBeCloseTo(before.top - 100, 0);
            expect(window.scrollY).toBe(scrollY);
        });

        it('glides out of a trackpad swipe without a step back, one event a frame and then sparser', async () => {
            const {fixture, host} = await mountZoomed();
            const at = centreOf(host);
            const start = nodeBoxes(host)[0]?.left ?? 0;
            const frame = (): Promise<void> => new Promise((resolve) => requestAnimationFrame(() => resolve()));
            // A swipe's momentum tail: shrinking deltas, first every frame, then every other frame.
            const deltas = [24, 22, 20, 18, 16, 14, 12, 10, 8, 7, 6, 5, 4, 3, 2, 2, 1, 1, 1, 1];
            const lefts: number[] = [];
            for (const [i, deltaX] of deltas.entries()) {
                wheelOver(host, {deltaX, clientX: at.x, clientY: at.y});
                await frame();
                lefts.push(nodeBoxes(host)[0]?.left ?? 0);
                if (i >= 10) {
                    await frame();
                    lefts.push(nodeBoxes(host)[0]?.left ?? 0);
                }
            }
            await settle(fixture);

            for (let i = 1; i < lefts.length; i++) {
                expect(lefts[i], `frame ${i} stepped back`).toBeLessThanOrEqual((lefts[i - 1] ?? 0) + 0.5);
            }
            const total = deltas.reduce((sum, d) => sum + d, 0);
            expect(nodeBoxes(host)[0]?.left ?? 0).toBeCloseTo(start - total, 0);
        });

        it('zooms by at most 15 % for one Ctrl wheel notch', async () => {
            const {fixture, host} = await mount();
            const scrollY = window.scrollY;
            const before = nodeBoxes(host)[0]?.width ?? 0;
            const at = centreOf(host);

            const notch = wheelOver(host, {deltaY: -100, ctrlKey: true, clientX: at.x, clientY: at.y});
            await settle(fixture);
            const ratio = (nodeBoxes(host)[0]?.width ?? 0) / before;
            expect(notch.defaultPrevented).toBe(true);
            expect(ratio).toBeGreaterThan(1.01);
            expect(ratio).toBeLessThanOrEqual(1.15 + 1e-6);

            wheelOver(host, {deltaY: 100, metaKey: true, clientX: at.x, clientY: at.y});
            await settle(fixture);
            expect((nodeBoxes(host)[0]?.width ?? 0) / before).toBeLessThan(ratio);
            expect(window.scrollY).toBe(scrollY);
        });

        it('zooms smoothly around the pointer under a pinch, a run of small Ctrl deltas inside one frame', async () => {
            const {fixture, host} = await mountZoomed();
            const scrollY = window.scrollY;
            const node = nodeBoxes(host)[5];
            if (node === undefined) throw new Error('no node');
            const px = node.left + node.width / 2;
            const py = node.top + node.height / 2;

            for (let i = 0; i < 10; i++) wheelOver(host, {deltaY: -8, ctrlKey: true, clientX: px, clientY: py});
            await settle(fixture);
            const after = nodeBoxes(host)[5];
            if (after === undefined) throw new Error('no node');
            const ratio = after.width / node.width;
            // Ten small steps compound, so they must all have built on each other, not on one stale viewport.
            expect(ratio).toBeCloseTo(Math.exp(80 * 0.0015), 2);
            expect(after.left + after.width / 2).toBeCloseTo(px, 0);
            expect(after.top + after.height / 2).toBeCloseTo(py, 0);
            expect(window.scrollY).toBe(scrollY);
        });
    });

    describe('panning is bounded by the graph (ISC-409)', () => {
        /** The union of every drawn node's box: the graph as the reader sees it. */
        function graphBox(host: HTMLElement): {left: number; right: number; top: number; bottom: number} {
            const rects = Array.from(host.querySelectorAll('[data-node]'), (n) => n.getBoundingClientRect());
            return {
                left: Math.min(...rects.map((r) => r.left)),
                right: Math.max(...rects.map((r) => r.right)),
                top: Math.min(...rects.map((r) => r.top)),
                bottom: Math.max(...rects.map((r) => r.bottom)),
            };
        }

        /** Wholly inside the box, or overlapping it by at least the margin on both axes. */
        function expectInReach(host: HTMLElement, whole: boolean, label: string): void {
            const box = host.getBoundingClientRect();
            const g = graphBox(host);
            if (whole) {
                expect(g.left, label).toBeGreaterThanOrEqual(box.left - 1);
                expect(g.right, label).toBeLessThanOrEqual(box.right + 1);
                expect(g.top, label).toBeGreaterThanOrEqual(box.top - 1);
                expect(g.bottom, label).toBeLessThanOrEqual(box.bottom + 1);
                return;
            }
            const overlapX = Math.min(g.right, box.right) - Math.max(g.left, box.left);
            const overlapY = Math.min(g.bottom, box.bottom) - Math.max(g.top, box.top);
            expect(overlapX, label).toBeGreaterThanOrEqual(Math.min(FLOW_PAN_MARGIN, g.right - g.left) - 1);
            expect(overlapY, label).toBeGreaterThanOrEqual(Math.min(FLOW_PAN_MARGIN, g.bottom - g.top) - 1);
        }

        /** A mouse drag on the canvas's own svg, the way d3-zoom reads one: down on the svg, moves and up on the window. */
        function drag(host: HTMLElement, dx: number, dy: number): void {
            const svg = host.querySelector('svg')!;
            const r = svg.getBoundingClientRect();
            const x = r.left + r.width / 2;
            const y = r.top + r.height / 2;
            const at = (px: number, py: number): MouseEventInit => ({bubbles: true, cancelable: true, clientX: px, clientY: py, button: 0, view: window});
            svg.dispatchEvent(new MouseEvent('mousedown', {...at(x, y), buttons: 1}));
            for (let step = 1; step <= 6; step++) {
                window.dispatchEvent(new MouseEvent('mousemove', {...at(x + (dx * step) / 6, y + (dy * step) / 6), buttons: 1}));
            }
            window.dispatchEvent(new MouseEvent('mouseup', at(x + dx, y + dy)));
        }

        function wheel(host: HTMLElement, dx: number, dy: number): void {
            const svg = host.querySelector('svg')!;
            // A wheel pans against its delta: to push the graph right, the wheel turns left.
            for (let i = 0; i < 20; i++) svg.dispatchEvent(new WheelEvent('wheel', {bubbles: true, cancelable: true, deltaX: -dx / 20, deltaY: -dy / 20}));
        }

        const PUSHES = [
            {dx: 4000, dy: 0, side: 'right'},
            {dx: -4000, dy: 0, side: 'left'},
            {dx: 0, dy: 4000, side: 'down'},
            {dx: 0, dy: -4000, side: 'up'},
        ];

        async function pushEverywhere(fixture: ComponentFixture<FlowCanvas>, host: HTMLElement, whole: boolean): Promise<void> {
            for (const {dx, dy, side} of PUSHES) {
                drag(host, dx, dy);
                await settle(fixture);
                expectInReach(host, whole, `drag ${side}`);
                wheel(host, dx, dy);
                await settle(fixture);
                expectInReach(host, whole, `wheel ${side}`);
            }
        }

        it('keeps the fitted graph wholly inside the box however far it is dragged or wheeled', async () => {
            const {fixture, host} = await mount();
            expectInReach(host, true, 'fit');
            await pushEverywhere(fixture, host, true);
        });

        it('keeps at least a margin of the graph in the box at the largest zoom', async () => {
            const {fixture, host} = await mount();
            for (let i = 0; i < 12; i++) button(host, 'zoom-in').click();
            await settle(fixture);
            await pushEverywhere(fixture, host, false);
        });

        it('keeps a margin in view after revealing a node from under the sheet, and after a drag from there', async () => {
            const {fixture, host} = await mount();
            const stand = document.createElement('div');
            stand.style.cssText = 'position: fixed; inset-block: 4rem 0; inset-inline: auto 0; inline-size: 48rem; z-index: 15;';
            document.body.appendChild(stand);
            fixture.componentRef.setInput('occluder', stand);
            for (let i = 0; i < 12; i++) button(host, 'zoom-in').click();
            await settle(fixture);

            fixture.componentRef.setInput('selected', 'DIGEST');
            await settle(fixture);
            await frames(2);
            expectInReach(host, false, 'reveal');
            drag(host, -4000, 0);
            await settle(fixture);
            expectInReach(host, false, 'drag after reveal');
        });
    });

    describe('beside the stage sheet (ISC-394)', () => {
        /** A stand-in with the sheet's geometry at 1440×900: fixed, right edge, 48rem wide. */
        function sheet(): HTMLElement {
            const stand = document.createElement('div');
            stand.style.cssText = 'position: fixed; inset-block: 4rem 0; inset-inline: auto 0; inline-size: 48rem; z-index: 15;';
            document.body.appendChild(stand);
            return stand;
        }

        function nodeOf(host: HTMLElement, stageId: string): DOMRect {
            const anchor = Array.from(host.querySelectorAll<HTMLElement>('lg-flow-node [data-stage]')).find((a) => a.dataset['stage'] === stageId);
            const node = anchor?.closest('[data-node]');
            if (node === null || node === undefined) throw new Error(`no node for ${stageId}`);
            return node.getBoundingClientRect();
        }

        function intersects(a: DOMRect, b: DOMRect): boolean {
            return a.left < b.right && a.right > b.left && a.top < b.bottom && a.bottom > b.top;
        }

        async function select(fixture: ComponentFixture<FlowCanvas>, stageId: string): Promise<void> {
            fixture.componentRef.setInput('selected', stageId);
            await settle(fixture);
            await frames(2);
        }

        beforeEach(async () => {
            await page.viewport(1440, 900);
        });

        it('pans DIGEST and then an ingest source out from under the sheet, keeping the zoom and the page still', async () => {
            const {fixture, host} = await mount();
            const stand = sheet();
            fixture.componentRef.setInput('occluder', stand);
            const scrollY = window.scrollY;
            const width = nodeOf(host, 'DEDUPE').width;
            // The premise: at the fit, DIGEST in the last column sits under the sheet.
            expect(intersects(nodeOf(host, 'DIGEST'), stand.getBoundingClientRect())).toBe(true);

            for (const stageId of ['DIGEST', 'INGEST alpha']) {
                await select(fixture, stageId);
                const node = nodeOf(host, stageId);
                const box = host.getBoundingClientRect();
                expect(intersects(node, stand.getBoundingClientRect())).toBe(false);
                expect(node.left).toBeGreaterThanOrEqual(box.left - 0.5);
                expect(node.top).toBeGreaterThanOrEqual(box.top - 0.5);
                expect(node.bottom).toBeLessThanOrEqual(box.bottom + 0.5);
                expect(Math.abs(nodeOf(host, 'DEDUPE').width - width)).toBeLessThan(0.5);
                expect(window.scrollY).toBe(scrollY);
            }
        });

        it('does not move a selected node the sheet leaves visible', async () => {
            const {fixture, host} = await mount();
            fixture.componentRef.setInput('occluder', sheet());
            const before = nodeOf(host, 'DEDUPE');

            await select(fixture, 'DEDUPE');
            const after = nodeOf(host, 'DEDUPE');
            expect(Math.abs(after.left - before.left)).toBeLessThan(0.5);
            expect(Math.abs(after.top - before.top)).toBeLessThan(0.5);
        });
    });
});

/** Any CSS colour to opaque sRGB through a 1×1 canvas; `contrast.browser.spec.ts` does the same. */
function rgb(css: string): Rgb {
    const ctx = document.createElement('canvas').getContext('2d', {willReadFrequently: true})!;
    ctx.fillStyle = css;
    ctx.fillRect(0, 0, 1, 1);
    const [r, g, b] = ctx.getImageData(0, 0, 1, 1).data;
    return {r, g, b};
}

/**
 * ISC-400, rendered: what ngx-vflow paints is not in any token table. Its default background is a
 * literal white on the root svg, which in the dark theme put dark-theme cards on a white sheet and in
 * the light theme hid the white cards in it. The canvas hands the library a theme token instead.
 */
describe.each(['lg-light', 'lg-dark'])('FlowCanvas under %s (ISC-400)', (theme) => {
    beforeEach(() => document.documentElement.setAttribute('data-theme', theme));
    afterEach(() => {
        document.documentElement.removeAttribute('data-theme');
        document.body.replaceChildren();
    });

    it('paints the graph on the theme\'s base-200, with the cards on base-100 and edges and arrows at ≥ 3:1', async () => {
        const {host} = await mount();
        const root = getComputedStyle(document.documentElement);
        const svg = host.querySelector<SVGSVGElement>('svg.root-svg')!;
        const ground = rgb(getComputedStyle(svg).backgroundColor);
        expect(ground, 'vflow background').toEqual(rgb(root.getPropertyValue('--color-base-200')));

        const card = host.querySelector<HTMLElement>('.flow-node')!;
        expect(rgb(getComputedStyle(card).backgroundColor), 'card').toEqual(rgb(root.getPropertyValue('--color-base-100')));

        const edge = host.querySelector<SVGPathElement>('path.flow-edge')!;
        expect(contrastRatio(rgb(getComputedStyle(edge).stroke), ground), 'edge').toBeGreaterThanOrEqual(3);
        const arrow = host.querySelector<SVGPolylineElement>('marker polyline')!;
        expect(contrastRatio(rgb(getComputedStyle(arrow).fill), ground), 'arrow').toBeGreaterThanOrEqual(3);
    });
});
