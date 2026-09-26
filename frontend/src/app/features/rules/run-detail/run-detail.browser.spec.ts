import {TestBed} from '@angular/core/testing';
import {provideRouter} from '@angular/router';
import {CurrentRunView} from '@core/model/current-run';
import {WorkflowView} from '@core/model/workflow';
import {runState} from '../run-state';
import {RunDetail} from './run-detail';

const WORKFLOW: WorkflowView = {
    phases: [
        {
            id: 'all',
            stages: ['DEDUPE', 'FILTER', 'SCORE'].map((id) => ({
                id,
                kind: 'stage' as const,
                sourceId: null,
                description: '',
                costClasses: ['free' as const],
                promptId: null,
                settings: [],
                knockouts: null,
                width: null,
            })),
        },
    ],
    unread: [],
};

const CURRENT: CurrentRunView = {
    id: 1,
    startedAt: '2026-09-27T00:46:00Z',
    scoreModel: null,
    stage: 'FILTER',
    stagePosition: 2,
    stageTotal: 3,
    stageStartedAt: null,
};

/** The colour a custom property resolves to, read the way the browser reads it on an element. */
function resolved(property: string): string {
    const probe = document.createElement('span');
    probe.style.color = `var(${property})`;
    document.body.appendChild(probe);
    const color = getComputedStyle(probe).color;
    probe.remove();
    return color;
}

describe('RunDetail in a browser', () => {
    it('draws the progress bar in the run hue, the one colour that means "being worked right now"', () => {
        // DaisyUI paints the bar in `currentColor` and sets `color` to the base content, and a
        // DOM-render screenshot draws a native <progress> in the platform's own green either way —
        // so this reads the computed colour rather than trusting a picture.
        TestBed.configureTestingModule({providers: [provideRouter([])]});
        const fixture = TestBed.createComponent(RunDetail);
        fixture.componentRef.setInput('current', CURRENT);
        fixture.componentRef.setInput('runState', runState(WORKFLOW, CURRENT));
        fixture.detectChanges();
        document.body.appendChild(fixture.nativeElement as HTMLElement);

        const bar = (fixture.nativeElement as HTMLElement).querySelector('progress.run-progress');

        expect(bar).not.toBeNull();
        expect(getComputedStyle(bar!).color).toBe(resolved('--lg-run'));
    });
});
