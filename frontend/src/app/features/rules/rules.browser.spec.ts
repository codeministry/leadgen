import {Location} from '@angular/common';
import {provideHttpClient} from '@angular/common/http';
import {HttpTestingController, provideHttpClientTesting} from '@angular/common/http/testing';
import {provideLocationMocks} from '@angular/common/testing';
import {TestBed} from '@angular/core/testing';
import {provideRouter, withComponentInputBinding} from '@angular/router';
import {RouterTestingHarness} from '@angular/router/testing';
import {page} from 'vitest/browser';
import {WorkflowStage, WorkflowView} from '@core/model/workflow';
import {Rules} from './rules';

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
        {id: 'sort', stages: [stage('DEDUPE'), stage('FILTER'), stage('ARCHIVE')]},
        {id: 'understand', stages: [stage('ENRICH'), stage('CONTENT'), stage('FIELDS')]},
        {id: 'judge', stages: [stage('SCORE'), stage('RETRIEVAL')]},
        {id: 'hand', stages: [stage('OPEN'), stage('PACKAGE'), stage('DIGEST')]},
    ],
    unread: [],
};

function frames(count = 2): Promise<void> {
    return new Promise((resolve) => {
        const step = (left: number): void => {
            if (left === 0) resolve();
            else requestAnimationFrame(() => step(left - 1));
        };
        step(count);
    });
}

/**
 * The sheet against a real canvas (ISC-403): a click on the canvas background closes it, a drag
 * that pans the canvas does not. jsdom has no layout and no d3 pan, so this half lives here.
 */
describe('Rules — closing the sheet from the canvas (ISC-403)', () => {
    let http: HttpTestingController;
    let harness: RouterTestingHarness;

    beforeEach(async () => {
        await page.viewport(1440, 900);
        TestBed.configureTestingModule({
            providers: [
                provideRouter([{path: 'rules', component: Rules}], withComponentInputBinding()),
                provideLocationMocks(),
                provideHttpClient(),
                provideHttpClientTesting(),
            ],
        });
        http = TestBed.inject(HttpTestingController);
        harness = await RouterTestingHarness.create();
        document.body.appendChild(harness.fixture.nativeElement as HTMLElement);
    });

    afterEach(() => {
        (harness.fixture.nativeElement as HTMLElement).remove();
    });

    async function settle(): Promise<void> {
        for (const request of http.match(() => true)) {
            if (request.cancelled) continue;
            if (request.request.url === '/api/v1/workflow') request.flush(WORKFLOW);
            else if (request.request.url === '/api/v1/scoring-models') request.flush({default: null, available: []});
            else request.flush(null, {status: 204, statusText: 'No Content'});
        }
        harness.detectChanges();
        await harness.fixture.whenStable();
        await frames(4);
        harness.detectChanges();
        await harness.fixture.whenStable();
        await frames(2);
    }

    function sheet(): HTMLElement | null {
        return document.querySelector('lg-stage-sheet [role="dialog"]');
    }

    /** A point on the canvas's own surface: inside the box, on no node, no control and not under the sheet. */
    function background(): {target: Element; x: number; y: number} {
        const box = document.querySelector('lg-flow-canvas')!.getBoundingClientRect();
        for (let y = box.top + 8; y < box.bottom - 8; y += 12) {
            for (let x = box.left + 8; x < box.right - 8; x += 12) {
                const target = document.elementFromPoint(x, y);
                if (
                    target !== null &&
                    target.closest('lg-flow-canvas') !== null &&
                    target.closest('lg-flow-node, button, a, lg-stage-sheet') === null
                ) {
                    return {target, x, y};
                }
            }
        }
        throw new Error('no free canvas background');
    }

    /** What a mouse sends, in the browser's order: both pointer and mouse events, since d3-zoom pans on the latter. */
    function press(target: Element, x: number, y: number, dx: number, dy: number): void {
        const at = (px: number, py: number): MouseEventInit => ({bubbles: true, cancelable: true, composed: true, clientX: px, clientY: py, button: 0, view: window});
        target.dispatchEvent(new PointerEvent('pointerdown', {...at(x, y), pointerId: 1, isPrimary: true, buttons: 1}));
        target.dispatchEvent(new MouseEvent('mousedown', {...at(x, y), buttons: 1}));
        if (dx !== 0 || dy !== 0) {
            for (const step of [0.5, 1]) {
                const px = x + dx * step;
                const py = y + dy * step;
                target.dispatchEvent(new PointerEvent('pointermove', {...at(px, py), pointerId: 1, isPrimary: true, buttons: 1}));
                window.dispatchEvent(new MouseEvent('mousemove', {...at(px, py), buttons: 1}));
            }
        }
        target.dispatchEvent(new PointerEvent('pointerup', {...at(x + dx, y + dy), pointerId: 1, isPrimary: true}));
        window.dispatchEvent(new MouseEvent('mouseup', at(x + dx, y + dy)));
        target.dispatchEvent(new MouseEvent('click', at(x + dx, y + dy)));
    }

    it('closes on a plain click on the canvas background', async () => {
        await harness.navigateByUrl('/rules?stage=DEDUPE');
        await settle();
        expect(sheet()).not.toBeNull();

        const {target, x, y} = background();
        press(target, x, y, 0, 0);
        await settle();

        expect(TestBed.inject(Location).path()).toBe('/rules');
        expect(sheet()).toBeNull();
    });

    it('stays open while a 20 px drag pans the canvas', async () => {
        await harness.navigateByUrl('/rules?stage=DEDUPE');
        await settle();
        const node = (): DOMRect => document.querySelector('lg-flow-canvas lg-flow-node')!.getBoundingClientRect();
        const before = node();

        const {target, x, y} = background();
        press(target, x, y, 20, 0);
        await settle();

        // The premise: the drag really panned.
        expect(Math.abs(node().left - before.left)).toBeGreaterThan(10);
        expect(TestBed.inject(Location).path()).toBe('/rules?stage=DEDUPE');
        expect(sheet()).not.toBeNull();
    });
});
