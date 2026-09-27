import {ComponentFixture, TestBed} from '@angular/core/testing';
import {provideRouter} from '@angular/router';
import {CurrentRunView} from '@core/model/current-run';
import {LastRunStage, LastRunView} from '@core/model/last-run';
import {WorkflowView} from '@core/model/workflow';
import {runState} from '../run-state';
import {RunDetail} from './run-detail';

const ORDER = ['INGEST mail', 'DEDUPE', 'FILTER', 'SCORE', 'RETRIEVAL', 'DIGEST'];

const WORKFLOW: WorkflowView = {
    phases: [
        {
            id: 'all',
            stages: ORDER.map((id) => ({
                id,
                kind: id.startsWith('INGEST') ? ('ingest' as const) : ('stage' as const),
                sourceId: id.startsWith('INGEST') ? 'mail' : null,
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

function stage(position: number, name: string, millis: number): LastRunStage {
    return {position, stage: name, startedAt: '', endedAt: '', millis, status: 'OK', note: null, width: null};
}

/** The run before: what each stage took then, which is all the estimate has to go on. */
const PREVIOUS: LastRunView = {
    finishedAt: '2026-09-26T23:00:00Z',
    status: 'COMPLETE',
    scoreModel: null,
    extracted: 0,
    written: 0,
    merged: 0,
    enriched: 0,
    removed: {},
    filterConsidered: 0,
    filterPassed: 0,
    scored: 0,
    shortlisted: 0,
    review: 0,
    packaged: 0,
    digestWritten: true,
    startedAt: '2026-09-02T04:00:00Z',
    previous: null,
    sources: [],
    stages: [
        stage(1, 'INGEST mail', 700),
        stage(2, 'DEDUPE', 79_000),
        stage(3, 'FILTER', 4_000),
        stage(4, 'SCORE', 13),
        stage(5, 'RETRIEVAL', 120_000),
        stage(6, 'DIGEST', 60),
    ],
};

function running(at: string): CurrentRunView {
    return {id: 153, startedAt: '2026-09-27T00:46:00Z', scoreModel: null, stage: at, stagePosition: 5, stageTotal: 6, stageStartedAt: null};
}

function render(current: CurrentRunView | null, lastRun: LastRunView | null, elapsed: number | null): ComponentFixture<RunDetail> {
    const fixture = TestBed.createComponent(RunDetail);
    fixture.componentRef.setInput('current', current);
    fixture.componentRef.setInput('lastRun', lastRun);
    fixture.componentRef.setInput('elapsed', elapsed);
    fixture.componentRef.setInput('runState', runState(WORKFLOW, current));
    fixture.componentRef.setInput('runElapsed', 198);
    fixture.detectChanges();
    return fixture;
}

function el(fixture: ComponentFixture<RunDetail>): HTMLElement {
    return fixture.nativeElement as HTMLElement;
}

describe('RunDetail — the pass in flight', () => {
    beforeEach(() => {
        TestBed.configureTestingModule({providers: [provideRouter([])]});
    });

    it('lists every stage in run order, placed against the running one by name', () => {
        const rows = Array.from(el(render(running('RETRIEVAL'), PREVIOUS, 111)).querySelectorAll<HTMLElement>('.run-step'));

        expect(rows.map((row) => row.dataset['stage'])).toEqual(ORDER);
        expect(rows.map((row) => row.dataset['state'])).toEqual(['done', 'done', 'done', 'done', 'running', 'pending']);
    });

    it('counts the bar from the list, not from the reported position', () => {
        const bar = el(render(running('RETRIEVAL'), PREVIOUS, 111)).querySelector<HTMLProgressElement>('progress.run-progress');

        expect(bar?.value).toBe(4);
        expect(bar?.max).toBe(6);
    });

    it('estimates what is left from the run before: the running stage\'s rest plus every stage not reached', () => {
        // RETRIEVAL took 120 s last time and has run 111 s: 9 s left of it, and DIGEST's 60 ms.
        const text = el(render(running('RETRIEVAL'), PREVIOUS, 111)).querySelector('.run-remaining')?.textContent ?? '';

        expect(text).toContain('9 s');
    });

    it('never counts a running stage past its previous time as negative', () => {
        const text = el(render(running('RETRIEVAL'), PREVIOUS, 400)).querySelector('.run-remaining');

        // Nothing left to estimate beyond DIGEST's 60 ms, which rounds to zero: no line at all.
        expect(text).toBeNull();
    });

    it('estimates nothing without a previous run to go by', () => {
        const page = el(render(running('RETRIEVAL'), null, 111));

        expect(page.querySelector('.run-remaining')).toBeNull();
        // The list still stands: where the pass is does not depend on the run before.
        expect(page.querySelectorAll('.run-step')).toHaveLength(ORDER.length);
    });

    it('shows the running stage\'s live seconds against its previous time, and a sub-second stage as under a second', () => {
        const page = el(render(running('RETRIEVAL'), PREVIOUS, 111));
        const time = (id: string): string =>
            page.querySelector(`.run-step[data-stage="${id}"] .run-step-time`)?.textContent?.replace(/\s+/g, ' ').trim() ?? '';

        expect(time('RETRIEVAL')).toBe('1:51 / ~2:00');
        expect(time('DEDUPE')).toBe('~1:19');
        expect(time('SCORE')).toBe('~<1 s');
    });

    it('lists nothing when no pass is running', () => {
        expect(el(render(null, PREVIOUS, null)).querySelector('[data-section="steps"]')).toBeNull();
    });
});

describe('RunDetail — the run that finished, against the one before', () => {
    beforeEach(() => {
        TestBed.configureTestingModule({providers: [provideRouter([])]});
    });

    const FINISHED: LastRunView = {
        ...PREVIOUS,
        startedAt: '2026-09-26T22:55:00Z',
        finishedAt: '2026-09-26T23:00:00Z',
        extracted: 169,
        shortlisted: 7,
        previous: {
            startedAt: '2026-09-26T20:00:00Z',
            finishedAt: '2026-09-26T20:06:00Z',
            status: 'COMPLETE',
            extracted: 169,
            written: 0,
            merged: 0,
            enriched: 0,
            filterConsidered: 0,
            filterPassed: 0,
            scored: 0,
            shortlisted: 4,
            review: 0,
            packaged: 0,
        },
    };

    function figure(page: HTMLElement, label: string): string {
        const terms = Array.from(page.querySelectorAll<HTMLElement>('[data-section="numbers"] dt'));
        const term = terms.find((dt) => dt.textContent?.trim() === label);
        return term?.nextElementSibling?.textContent?.replace(/\s+/g, ' ').trim() ?? '';
    }

    it('puts the change since the run before beside a figure that moved, and nothing beside one that did not', () => {
        const page = el(render(null, FINISHED, null));

        expect(figure(page, 'Shortlisted')).toBe('+3 7');
        expect(figure(page, 'Read')).toBe('169');
        expect(page.querySelector('.run-delta-note')?.textContent).toContain('run before');
    });

    it('says how long the run took, and by how much that changed', () => {
        const facts = el(render(null, FINISHED, null)).querySelector('.run-head .run-facts')?.textContent?.replace(/\s+/g, ' ') ?? '';

        expect(facts).toContain('took 5:00');
        expect(facts).toContain('-1:00');
    });

    it('shows no change at all without a run before', () => {
        const page = el(render(null, {...FINISHED, previous: null}, null));

        expect(page.querySelectorAll('.run-delta')).toHaveLength(0);
        expect(figure(page, 'Shortlisted')).toBe('7');
    });
});

describe('RunDetail — the day\'s model calls', () => {
    beforeEach(() => {
        TestBed.configureTestingModule({providers: [provideRouter([])]});
    });

    function withBudget(budget: {used: number; limit: number | null} | null): HTMLElement {
        const fixture = TestBed.createComponent(RunDetail);
        fixture.componentRef.setInput('lastRun', PREVIOUS);
        fixture.componentRef.setInput('budget', budget);
        fixture.detectChanges();
        return fixture.nativeElement as HTMLElement;
    }

    it('says today\'s calls against the ceiling', () => {
        expect(withBudget({used: 12, limit: 200}).querySelector('[data-budget]')?.textContent).toContain('12 of 200');
    });

    it('says so when there is no ceiling, rather than showing one of zero', () => {
        expect(withBudget({used: 12, limit: null}).querySelector('[data-budget]')?.textContent).toContain('no daily limit');
    });

    it('marks the day as spent once the ceiling is reached, and only then', () => {
        expect(withBudget({used: 200, limit: 200}).querySelector('[data-budget] lg-badge')).not.toBeNull();
        expect(withBudget({used: 199, limit: 200}).querySelector('[data-budget] lg-badge')).toBeNull();
    });

    it('says nothing before the count has been read', () => {
        expect(withBudget(null).querySelector('[data-budget]')).toBeNull();
    });
});
