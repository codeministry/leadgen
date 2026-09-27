import {ComponentFixture, TestBed} from '@angular/core/testing';
import {page} from 'vitest/browser';
import {FunnelView} from '@core/model/funnel';
import {WorkflowStage} from '@core/model/workflow';
import {StageSheet} from './stage-sheet';

/** The live shape of FILTER's funnel: long German-length labels and five-digit counts. */
const FUNNEL: FunnelView = {
    total: 12530,
    stages: [
        {id: 'abroad', label: 'Abroad', removed: 9},
        {id: 'remote', label: 'Remote share below the minimum', removed: 0},
        {id: 'rate', label: 'Beyond reach, not remote', removed: 12295},
        {id: 'stack', label: 'Foreign stack or wrong role', removed: 58},
        {id: 'excluded', label: 'No core skill', removed: 86},
        {id: 'duplicate', label: 'Contract form rejected', removed: 6},
    ],
    survived: 76,
    archived: 0,
};

const FILTER: WorkflowStage = {
    id: 'FILTER',
    kind: 'stage',
    sourceId: null,
    description: 'Judges every offer against the six knockouts, again on every run.',
    costClasses: ['free'],
    promptId: null,
    settings: [{key: 'remote.accept_unknown', value: 'true', file: 'matching-rules.yaml'}],
    knockouts: null,
    width: null,
};

function frames(count: number): Promise<void> {
    return new Promise((resolve) => {
        const step = (left: number): void => (left === 0 ? resolve() : void requestAnimationFrame(() => step(left - 1)));
        step(count);
    });
}

/**
 * ISC-393, refined: the sheet sits at the right edge of the browser window, from under the
 * app header to the bottom of the viewport, and the funnel inside it never clips. Only a real
 * layout can say either; jsdom lays nothing out.
 */
describe('StageSheet in a browser', () => {
    let fixture: ComponentFixture<StageSheet>;

    beforeEach(async () => {
        await page.viewport(1440, 900);
        fixture = TestBed.createComponent(StageSheet);
        fixture.componentRef.setInput('stage', FILTER);
        fixture.componentRef.setInput('funnel', FUNNEL);
        // Inside a narrow, offset box, the way the screen used to hold it: a fixed sheet ignores it.
        const frame = document.createElement('div');
        frame.style.cssText = 'position: relative; inline-size: 600px; block-size: 300px; margin: 40px;';
        document.body.appendChild(frame);
        frame.appendChild(fixture.nativeElement as HTMLElement);
        fixture.detectChanges();
        await fixture.whenStable();
        await frames(3);
    });

    afterEach(() => document.body.replaceChildren());

    it('is fixed at the right edge of the window, from under the header to the bottom', () => {
        const host = fixture.nativeElement as HTMLElement;
        const box = host.getBoundingClientRect();
        // The header token resolved by the browser, in whatever the root font size makes of it.
        const probe = document.createElement('div');
        probe.style.blockSize = 'var(--lg-header-h)';
        document.body.appendChild(probe);
        const headerHeight = probe.getBoundingClientRect().height;
        expect(headerHeight).toBeGreaterThan(0);

        expect(getComputedStyle(host).position).toBe('fixed');
        expect(Math.abs(box.right - window.innerWidth)).toBeLessThan(0.5);
        expect(Math.abs(box.top - headerHeight)).toBeLessThan(0.5);
        expect(Math.abs(box.bottom - window.innerHeight)).toBeLessThan(0.5);
        expect(box.width).toBeLessThanOrEqual(window.innerWidth / 2);
    });

    it('fits the funnel rail into its width, with no row wider than the sheet', () => {
        const host = fixture.nativeElement as HTMLElement;
        const body = host.querySelector<HTMLElement>('.sheet-body');
        const rail = host.querySelector<HTMLElement>('lg-funnel-rail');
        if (body === null || rail === null) throw new Error('no sheet body or funnel rail');

        expect(body.scrollWidth).toBeLessThanOrEqual(body.clientWidth);
        expect(rail.scrollWidth).toBeLessThanOrEqual(rail.clientWidth);
        const edge = body.getBoundingClientRect().right;
        for (const drop of Array.from(rail.querySelectorAll<HTMLElement>('.drop, .count'))) {
            expect(drop.getBoundingClientRect().right).toBeLessThanOrEqual(edge + 0.5);
        }
    });
});
