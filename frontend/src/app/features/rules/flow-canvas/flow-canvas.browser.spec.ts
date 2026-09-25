import {ComponentFixture, TestBed} from '@angular/core/testing';
import {provideRouter} from '@angular/router';
import {page} from 'vitest/browser';
import {WorkflowStage, WorkflowView} from '@core/model/workflow';
import {FlowCanvas} from './flow-canvas';

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
async function mount(): Promise<{fixture: ComponentFixture<FlowCanvas>; host: HTMLElement; frame: HTMLElement}> {
    TestBed.configureTestingModule({imports: [FlowCanvas], providers: [provideRouter([])]});
    const frame = document.createElement('div');
    frame.style.inlineSize = '1200px';
    document.body.appendChild(frame);
    const fixture = TestBed.createComponent(FlowCanvas);
    fixture.componentRef.setInput('workflow', WORKFLOW);
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
