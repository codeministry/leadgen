import {TestBed} from '@angular/core/testing';
import {provideRouter} from '@angular/router';
import {page} from 'vitest/browser';
import {WorkflowStage, WorkflowView} from '@core/model/workflow';
import {StageRail} from './stage-rail';

function stage(id: string, costClasses: readonly string[], sourceId: string | null = null): WorkflowStage {
    return {
        id,
        kind: sourceId === null ? 'stage' : 'ingest',
        sourceId,
        description: `What ${id} does.`,
        costClasses,
        promptId: null,
        settings: [],
        knockouts: null,
        width: null,
    };
}

/** The live shape: four sources, one of them with a long id, and the widest chips a run leaves. */
const WORKFLOW: WorkflowView = {
    phases: [
        {
            id: 'read',
            stages: [
                stage('INGEST newsletter', ['network', 'model'], 'freelance-newsletter-weekly-digest'),
                stage('INGEST html', ['network'], 'html'),
                stage('INGEST mail', ['network'], 'mail'),
                stage('INGEST inbox', ['file'], 'inbox'),
            ],
        },
        {id: 'sort', stages: [stage('DEDUPE', ['free', 'model']), stage('FILTER', ['free']), stage('ARCHIVE', ['free'])]},
        {id: 'understand', stages: [stage('ENRICH', ['network']), stage('CONTENT', ['model']), stage('FIELDS', ['free'])]},
        {id: 'judge', stages: [stage('SCORE', ['model']), stage('RETRIEVAL', ['model'])]},
        {id: 'hand', stages: [stage('OPEN', ['free']), stage('PACKAGE', ['file']), stage('DIGEST', ['file'])]},
    ],
    unread: [],
};

const COUNTS = {'INGEST newsletter': 1204, 'INGEST html': 88, FILTER: 12548, ENRICH: 312, SCORE: 12537, PACKAGE: 3};

async function mount(width: number): Promise<HTMLElement> {
    TestBed.configureTestingModule({imports: [StageRail], providers: [provideRouter([])]});
    const frame = document.createElement('div');
    frame.style.inlineSize = `${width}px`;
    document.body.appendChild(frame);
    const fixture = TestBed.createComponent(StageRail);
    fixture.componentRef.setInput('workflow', WORKFLOW);
    fixture.componentRef.setInput('selected', 'FILTER');
    fixture.componentRef.setInput('counts', COUNTS);
    frame.appendChild(fixture.nativeElement as HTMLElement);
    fixture.detectChanges();
    await fixture.whenStable();
    return frame;
}

describe('StageRail at phone widths (ISC-395)', () => {
    afterEach(() => document.body.querySelectorAll(':scope > div').forEach((div) => div.remove()));

    // 375 and 320 are the viewports; 288 is 320 minus the 16 px gutter on each side.
    for (const width of [375, 320, 288]) {
        it(`keeps every pill inside a ${width} px column without scrolling it sideways`, async () => {
            await page.viewport(Math.max(width, 320), 800);
            const frame = await mount(width);
            const box = frame.getBoundingClientRect();
            const pills = Array.from(frame.querySelectorAll<HTMLElement>('a.stage-pill'));

            expect(pills.length).toBeGreaterThan(0);
            for (const pill of pills) {
                const rect = pill.getBoundingClientRect();
                expect(rect.width).toBeGreaterThan(0);
                expect(rect.right).toBeLessThanOrEqual(box.right + 0.5);
            }
            expect(frame.scrollWidth).toBe(frame.clientWidth);
            expect(document.documentElement.scrollWidth).toBe(document.documentElement.clientWidth);
        });
    }

    it('draws one continuous spine: each stage segment meets the next', async () => {
        await page.viewport(375, 800);
        const frame = await mount(375);
        const segments = Array.from(frame.querySelectorAll<HTMLElement>('.phase-stages .rail-spine'), (s) => s.getBoundingClientRect());

        expect(segments.length).toBeGreaterThan(1);
        for (const rect of segments) {
            expect(rect.width).toBe(2);
            expect(rect.height).toBeGreaterThan(0);
        }
        // Within a phase the rows touch, so the spine has no gap between two consecutive stages.
        const dedupe = frame.querySelector('a[data-stage="DEDUPE"]')?.closest('li')?.querySelector('.rail-spine')?.getBoundingClientRect();
        const filter = frame.querySelector('a[data-stage="FILTER"]')?.closest('li')?.querySelector('.rail-spine')?.getBoundingClientRect();
        expect(Math.abs((filter?.top ?? 0) - (dedupe?.bottom ?? -10))).toBeLessThanOrEqual(0.5);
    });
});
