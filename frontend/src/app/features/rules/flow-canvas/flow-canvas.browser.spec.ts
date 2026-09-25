import {TestBed} from '@angular/core/testing';
import {provideRouter} from '@angular/router';
import {WorkflowStage, WorkflowView} from '@core/model/workflow';
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

const WORKFLOW: WorkflowView = {
    phases: [
        {id: 'read', stages: [stage('INGEST zeta', 'zeta'), stage('INGEST alpha', 'alpha')]},
        {id: 'sort', stages: [stage('DEDUPE'), stage('FILTER')]},
        {id: 'hand', stages: [stage('DIGEST')]},
    ],
    unread: [],
};

/**
 * In headless Chromium the nodes get real boxes — the one thing jsdom cannot tell us,
 * because it lays nothing out.
 */
describe('FlowCanvas in a browser', () => {
    it('renders every node at the layout size, left to right, and draws the edges', async () => {
        TestBed.configureTestingModule({imports: [FlowCanvas], providers: [provideRouter([])]});
        const fixture = TestBed.createComponent(FlowCanvas);
        fixture.componentRef.setInput('workflow', WORKFLOW);
        const host = fixture.nativeElement as HTMLElement;
        document.body.appendChild(host);
        await fixture.whenStable();
        await new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve)));
        await fixture.whenStable();

        const nodes = Array.from(host.querySelectorAll<HTMLElement>('.flow-node'));
        expect(nodes.map((node) => node.textContent?.trim())).toEqual(['zeta', 'alpha', 'DEDUPE', 'FILTER', 'DIGEST']);
        const boxes = nodes.map((node) => node.getBoundingClientRect());
        for (const box of boxes) {
            // The rendered box is the one dagre laid out with, at the initial zoom of 1.
            expect(Math.round(box.width)).toBe(FLOW_NODE_SIZES.stage.width);
            expect(Math.round(box.height)).toBe(FLOW_NODE_SIZES.stage.height);
        }
        const [zeta, alpha, dedupe, filter, digest] = boxes;
        expect(zeta?.top).toBeLessThan(alpha?.top ?? 0);
        expect(zeta?.left).toBeLessThan(dedupe?.left ?? 0);
        expect(dedupe?.left).toBeLessThan(filter?.left ?? 0);
        expect(filter?.left).toBeLessThan(digest?.left ?? 0);
        expect(host.querySelectorAll('svg path').length).toBeGreaterThan(0);
        host.remove();
    });
});
