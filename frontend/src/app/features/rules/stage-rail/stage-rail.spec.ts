import {ComponentFixture, TestBed} from '@angular/core/testing';
import {provideRouter} from '@angular/router';
import {CurrentRunView} from '@core/model/current-run';
import {LastRunStage, LastRunView} from '@core/model/last-run';
import {WorkflowStage, WorkflowView} from '@core/model/workflow';
import {RunState, runState} from '../run-state';
import {COST_ICONS, StageRail} from './stage-rail';

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

/** Five phases in the server's order, with two ingest entries deliberately not alphabetical. */
const WORKFLOW: WorkflowView = {
    phases: [
        {id: 'read', stages: [stage('INGEST zeta', ['network', 'model'], 'zeta'), stage('INGEST alpha', ['file'], 'alpha')]},
        {id: 'sort', stages: [stage('DEDUPE', ['free']), stage('FILTER', ['free']), stage('ARCHIVE', ['free'])]},
        {id: 'understand', stages: [stage('ENRICH', ['network']), stage('CONTENT', ['model']), stage('FIELDS', ['free'])]},
        {id: 'judge', stages: [stage('SCORE', ['model']), stage('RETRIEVAL', ['model'])]},
        {id: 'hand', stages: [stage('OPEN', ['free']), stage('PACKAGE', ['file']), stage('DIGEST', ['file'])]},
    ],
    unread: [{key: 'legacy.flag', value: 'true', file: 'pipeline.yaml'}],
};

const STAGE_COUNT = WORKFLOW.phases.reduce((sum, phase) => sum + phase.stages.length, 0);

function runStage(position: number, name: string, status: 'OK' | 'FAILED' = 'OK'): LastRunStage {
    return {
        position,
        stage: name,
        startedAt: '2026-09-24T04:00:00Z',
        endedAt: '2026-09-24T04:01:00Z',
        millis: 60_000,
        status,
        note: status === 'FAILED' ? 'boom' : null,
        width: null,
    };
}

/** ISC-290's fixture: one run with ENRICH the stage that failed, everything before it OK. */
function lastRun(stages: readonly LastRunStage[]): LastRunView {
    return {
        finishedAt: '2026-09-24T04:13:00Z',
        status: stages.some((s) => s.status === 'FAILED') ? 'FAILED' : 'COMPLETE',
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
        digestWritten: false,
        sources: [],
        stages,
    };
}

describe('StageRail', () => {
    beforeEach(() => {
        TestBed.configureTestingModule({providers: [provideRouter([])]});
    });

    function render(
        selected: string,
        counts: Record<string, string | number | null> = {},
        run: LastRunView | null = null,
    ): ComponentFixture<StageRail> {
        const fixture = TestBed.createComponent(StageRail);
        fixture.componentRef.setInput('workflow', WORKFLOW);
        fixture.componentRef.setInput('selected', selected);
        fixture.componentRef.setInput('counts', counts);
        fixture.componentRef.setInput('lastRun', run);
        fixture.detectChanges();
        return fixture;
    }

    function element(fixture: ComponentFixture<StageRail>): HTMLElement {
        return fixture.nativeElement as HTMLElement;
    }

    it('is a named navigation landmark', () => {
        const nav = element(render('DEDUPE')).querySelector('nav');

        expect(nav?.getAttribute('aria-label')).toBe('Pipeline stages');
    });

    it('renders the five phases as headings in the order the server sent them', () => {
        const headings = Array.from(element(render('DEDUPE')).querySelectorAll('.phase-title'), (h) => h.textContent?.trim());

        expect(headings).toEqual(['Read', 'Sort', 'Understand', 'Judge', 'Hand over']);
    });

    describe('reads as one workflow (ISC-307)', () => {
        it('orders the five phases as an ordered list, one item per phase', () => {
            const rail = element(render('DEDUPE'));
            const list = rail.querySelector('nav ol.rail-phases');

            expect(list).not.toBeNull();
            expect(list?.querySelectorAll(':scope > li')).toHaveLength(5);
        });

        it('numbers the five step headings 1 to 5, each with its own icon', () => {
            const headings = Array.from(element(render('DEDUPE')).querySelectorAll('ol.rail-phases > li h2'));

            expect(headings).toHaveLength(5);
            expect(headings.map((h) => h.querySelector('.step-number')?.textContent?.trim())).toEqual(['1', '2', '3', '4', '5']);
            expect(headings.map((h) => h.querySelector('.phase-title')?.textContent?.trim())).toEqual([
                'Read',
                'Sort',
                'Understand',
                'Judge',
                'Hand over',
            ]);
            const icons = headings.map((h) => h.querySelector('.phase-icon svg'));
            expect(icons.every((svg) => svg !== null)).toBe(true);
            // Beside a visible word the icon is decorative, or the heading is read twice.
            expect(icons.every((svg) => svg?.getAttribute('aria-hidden') === 'true')).toBe(true);
            // Five different glyphs: a phase's icon is its own, not a shared bullet.
            expect(new Set(icons.map((svg) => svg?.innerHTML)).size).toBe(5);
        });

        it('joins the phases with four hidden spine segments, none after the last', () => {
            const rail = element(render('DEDUPE'));
            const items = Array.from(rail.querySelectorAll('ol.rail-phases > li'));
            const segments = items.map((li) => li.querySelectorAll(':scope > .rail-spine-phase'));

            expect(segments.map((found) => found.length)).toEqual([1, 1, 1, 1, 0]);
            for (const found of segments.slice(0, 4)) {
                expect(found[0].getAttribute('aria-hidden')).toBe('true');
            }
            // 008's arrows are gone: the spine draws the order now.
            expect(rail.querySelector('.phase-arrow')).toBeNull();
        });

        it('keeps the entry for unread keys outside the numbered flow', () => {
            const rail = element(render('DEDUPE'));

            expect(rail.querySelector('ol.rail-phases [data-stage="unread"]')).toBeNull();
            expect(rail.querySelector('[data-stage="unread"]')).not.toBeNull();
        });
    });

    it('lists every stage in server order, ingest entries by their source id, and the unread entry last', () => {
        const links = Array.from(element(render('DEDUPE')).querySelectorAll('nav a'));
        const labels = links.map((a) => a.querySelector('.stage-name')?.textContent?.trim());

        expect(labels).toEqual([
            'zeta',
            'alpha',
            'Deduplicate',
            'Hard filter',
            'Archive',
            'Enrich',
            'Content',
            'Fields',
            'Score',
            'Retrieval',
            'Open applications',
            'Package',
            'Digest',
            'Read by nothing',
        ]);
        expect(links).toHaveLength(STAGE_COUNT + 1);
    });

    it('links every entry to its own `stage` query parameter', () => {
        const hrefs = Array.from(element(render('DEDUPE')).querySelectorAll('nav a'), (a) => a.getAttribute('href'));

        expect(hrefs[0]).toBe('/?stage=INGEST%20zeta');
        expect(hrefs[2]).toBe('/?stage=DEDUPE');
        expect(hrefs.at(-1)).toBe('/?stage=unread');
    });

    it('gives every cost class an icon with an accessible name, never colour alone', () => {
        const rail = element(render('DEDUPE'));
        const expected = WORKFLOW.phases.flatMap((phase) => phase.stages).reduce((sum, s) => sum + s.costClasses.length, 0);
        const icons = rail.querySelectorAll('.stage-costs svg[role="img"]');

        expect(icons).toHaveLength(expected);
        const first = Array.from(rail.querySelectorAll('nav a')[0].querySelectorAll('.stage-costs svg'), (svg) =>
            svg.getAttribute('aria-label'),
        );
        expect(first).toEqual(['Leaves the machine', 'Computes embeddings with a model']);
        for (const link of Array.from(rail.querySelectorAll('nav a')).slice(0, STAGE_COUNT)) {
            expect(link.querySelectorAll('.stage-costs svg[aria-label]').length).toBeGreaterThan(0);
        }
    });

    it('names the model call a row makes: a prompt, or embeddings when the stage sends none', () => {
        const fixture = render('DEDUPE');
        const withPrompt: WorkflowView = {
            ...WORKFLOW,
            phases: WORKFLOW.phases.map((phase) => ({
                ...phase,
                stages: phase.stages.map((s) => (s.id === 'SCORE' ? {...s, promptId: 'scoring'} : s)),
            })),
        };
        fixture.componentRef.setInput('workflow', withPrompt);
        fixture.detectChanges();
        const labelOf = (id: string): (string | null)[] =>
            Array.from(element(fixture).querySelectorAll(`a[data-stage="${id}"] .stage-costs svg`), (svg) =>
                svg.getAttribute('aria-label'),
            );

        expect(labelOf('SCORE')).toEqual(['Asks a language model']);
        expect(labelOf('RETRIEVAL')).toEqual(['Computes embeddings with a model']);
    });

    it('marks the selected stage, and only that one, as the current page', () => {
        const current = Array.from(element(render('FILTER')).querySelectorAll('[aria-current="page"]'));

        expect(current).toHaveLength(1);
        expect(current[0].querySelector('.stage-name')?.textContent?.trim()).toBe('Hard filter');
    });

    it('can select the unread entry', () => {
        const current = element(render('unread')).querySelectorAll('[aria-current="page"]');

        expect(current).toHaveLength(1);
        expect(current[0].querySelector('.stage-name')?.textContent?.trim()).toBe('Read by nothing');
    });

    it('shows a count as the chip the canvas draws, with its verb, and nothing where none is', () => {
        const rail = element(render('DEDUPE', {'INGEST zeta': 8, FILTER: 12548, SCORE: 12537, ENRICH: null}));
        const count = (id: string) => rail.querySelector(`a[data-stage="${id}"] .stage-count`)?.textContent?.trim() ?? null;

        expect(count('INGEST zeta')).toBe('8 read');
        expect(count('FILTER')).toBe('−12,548 held back');
        expect(count('SCORE')).toBe('12,537 scored');
        expect(count('ENRICH')).toBeNull();
        expect(count('DEDUPE')).toBeNull();
    });

    it('never paints the signal and never offers a primary action', () => {
        const rail = element(render('DEDUPE'));

        expect(rail.querySelector('.btn-primary')).toBeNull();
        expect(rail.innerHTML).not.toContain('signal');
    });

    it('says so in words when there is no finished run, and shows no count at all', () => {
        // `counts` empty, as `stageCounts` itself resolves it without a last run (spec 008
        // § Decisions) — the rail's own guarantee is the words, not a second filter on counts
        // the parent already leaves empty.
        const rail = element(render('DEDUPE', {}, null));

        expect(rail.textContent).toContain('No run yet');
        expect(rail.querySelectorAll('.stage-count')).toHaveLength(0);
    });

    it('says nothing about a missing run once one exists', () => {
        const rail = element(render('DEDUPE', {}, lastRun([runStage(0, 'DEDUPE')])));

        expect(rail.textContent).not.toContain('No run yet');
    });

    it('marks the one stage the recorded run failed in, and only that one', () => {
        const rail = element(
            render('DEDUPE', {}, lastRun([runStage(0, 'DEDUPE'), runStage(1, 'ENRICH', 'FAILED')])),
        );
        const marker = (id: string) => rail.querySelector(`a[data-stage="${id}"] .stage-failed [role="img"][aria-label]`);

        expect(marker('ENRICH')).not.toBeNull();
        expect(marker('ENRICH')?.getAttribute('aria-label')).toBeTruthy();
        expect(marker('DEDUPE')).toBeNull();
        expect(marker('FILTER')).toBeNull();
    });

    it('carries no failed marker when there is no run', () => {
        const rail = element(render('DEDUPE'));

        expect(rail.querySelector('[data-stage="ENRICH"] .stage-failed')).toBeNull();
    });

    it('puts the count before the icons, the icons at the row end', () => {
        const link = element(render('DEDUPE', {SCORE: 12537})).querySelector('a[data-stage="SCORE"]');
        const order = Array.from(link?.children ?? [], (child) => child.classList[0]);

        expect(order).toEqual(['type-small', 'type-caption', 'stage-icons']);
        expect(link?.querySelector('.stage-icons .stage-costs')).not.toBeNull();
    });
});

describe('StageRail marks the AI steps (ISC-308)', () => {
    /** The model stages as the server sends them: a prompt-sending ingest, DEDUPE's embeddings, CONTENT, SCORE. */
    const AI_WORKFLOW: WorkflowView = {
        phases: [
            {
                id: 'read',
                stages: [
                    {...stage('INGEST llm', ['network'], 'llm'), promptId: 'extraction'},
                    stage('INGEST html', ['network'], 'html'),
                ],
            },
            {id: 'sort', stages: [stage('DEDUPE', ['free', 'model']), stage('FILTER', ['free'])]},
            {id: 'understand', stages: [stage('CONTENT', ['model'])]},
            {id: 'judge', stages: [{...stage('SCORE', ['model']), promptId: 'scoring'}]},
            {id: 'hand', stages: [stage('PACKAGE', ['file'])]},
        ],
        unread: [],
    };

    beforeEach(() => {
        TestBed.configureTestingModule({providers: [provideRouter([])]});
    });

    function rail(selected = 'FILTER'): HTMLElement {
        const fixture = TestBed.createComponent(StageRail);
        fixture.componentRef.setInput('workflow', AI_WORKFLOW);
        fixture.componentRef.setInput('selected', selected);
        fixture.detectChanges();
        return fixture.nativeElement as HTMLElement;
    }

    const marker = (root: HTMLElement, id: string) =>
        root.querySelector(`a[data-stage="${id}"] .stage-ai [role="img"][aria-label]`);

    it('puts a named sparkle marker on exactly the model stages', () => {
        const root = rail();
        const marked = Array.from(root.querySelectorAll('a[data-stage]'))
            .filter((link) => link.querySelector('.stage-ai [role="img"][aria-label]') !== null)
            .map((link) => link.getAttribute('data-stage'));

        expect(marked).toEqual(['INGEST llm', 'DEDUPE', 'CONTENT', 'SCORE']);
        expect(marker(root, 'SCORE')?.getAttribute('aria-label')).toBe('AI step');
    });

    it('draws no marker on FILTER, a non-prompt ingest source or PACKAGE', () => {
        const root = rail();

        expect(marker(root, 'FILTER')).toBeNull();
        expect(marker(root, 'INGEST html')).toBeNull();
        expect(marker(root, 'PACKAGE')).toBeNull();
    });

    it('flags the AI rows with is-ai for the edge, and keeps it when selected', () => {
        const root = rail('SCORE');
        const row = (id: string) => root.querySelector(`a[data-stage="${id}"]`)?.closest('.stage-row');

        expect(row('SCORE')?.classList.contains('is-ai')).toBe(true);
        expect(root.querySelector('a[data-stage="SCORE"]')?.classList.contains('is-selected')).toBe(true);
        expect(row('CONTENT')?.classList.contains('is-ai')).toBe(true);
        expect(row('FILTER')?.classList.contains('is-ai')).toBe(false);
    });

    it('keeps the cost-class icons beside the marker', () => {
        const root = rail();

        expect(root.querySelector('a[data-stage="SCORE"] .stage-costs [aria-label="Asks a language model"]')).not.toBeNull();
    });
});

// The legend moved to lg-flow-legend, one implementation under the canvas and the pipe alike
// (ISC-395); its entries and the icons-subset check are in flow-legend and rules.spec.ts.
describe('StageRail markers (ISC-310)', () => {
    beforeEach(() => {
        TestBed.configureTestingModule({providers: [provideRouter([])]});
    });

    /** Every run stage failed, so the rail draws every marker it has. */
    function rail(): HTMLElement {
        const all = WORKFLOW.phases.flatMap((phase) => phase.stages);
        const fixture = TestBed.createComponent(StageRail);
        fixture.componentRef.setInput('workflow', WORKFLOW);
        fixture.componentRef.setInput('selected', 'DEDUPE');
        fixture.componentRef.setInput('lastRun', lastRun(all.map((s, i) => runStage(i + 1, s.id, 'FAILED'))));
        fixture.detectChanges();
        return fixture.nativeElement as HTMLElement;
    }

    it('draws an icon for every cost class the server sends, so the fallback never reaches a row', () => {
        // WorkflowView.COST_* on the server; a fifth class would draw `ellipsis`, which the legend lacks.
        expect(Object.keys(COST_ICONS).sort()).toEqual(['file', 'free', 'model', 'network']);
    });

    it('keeps list semantics on the phases and the stages', () => {
        const root = rail();
        const lists = root.querySelectorAll('ol.rail-phases, ul.stages');

        expect(lists.length).toBeGreaterThan(1);
        lists.forEach((list) => expect(list.getAttribute('role')).toBe('list'));
    });
});

describe('StageRail is a flow pipe (ISC-395)', () => {
    beforeEach(() => {
        TestBed.configureTestingModule({providers: [provideRouter([])]});
    });

    function rail(selected = 'FILTER'): HTMLElement {
        const fixture = TestBed.createComponent(StageRail);
        fixture.componentRef.setInput('workflow', WORKFLOW);
        fixture.componentRef.setInput('selected', selected);
        fixture.detectChanges();
        return fixture.nativeElement as HTMLElement;
    }

    const ALL = WORKFLOW.phases.flatMap((phase) => phase.stages);
    const SOURCES = ALL.filter((s) => s.kind === 'ingest');
    const SPINE_STAGES = ALL.filter((s) => s.kind !== 'ingest');

    it('draws a hidden spine segment on every stage from the merge on', () => {
        const rows = Array.from(rail().querySelectorAll('ol.rail-phases .stage-row:not(.is-source)'));

        expect(rows.map((row) => row.querySelector('a')?.getAttribute('data-stage'))).toEqual(SPINE_STAGES.map((s) => s.id));
        for (const row of rows) {
            const spine = row.querySelector(':scope > .rail-spine');
            expect(spine).not.toBeNull();
            expect(spine?.getAttribute('aria-hidden')).toBe('true');
        }
    });

    it('brackets the sources, one hidden branch per source, merging before DEDUPE', () => {
        const root = rail();
        const bracket = root.querySelector('.rail-sources');
        const branches = Array.from(bracket?.querySelectorAll(':scope > li > .rail-branch') ?? []);

        expect(bracket).not.toBeNull();
        expect(branches).toHaveLength(SOURCES.length);
        expect(branches.every((b) => b.getAttribute('aria-hidden') === 'true')).toBe(true);
        expect(Array.from(bracket?.querySelectorAll('a') ?? [], (a) => a.getAttribute('data-stage'))).toEqual(SOURCES.map((s) => s.id));
        const merge = root.querySelector('.rail-merge');
        expect(merge?.getAttribute('aria-hidden')).toBe('true');
        const dedupe = root.querySelector('a[data-stage="DEDUPE"]') as Node;
        expect(merge?.compareDocumentPosition(dedupe)).toBe(Node.DOCUMENT_POSITION_FOLLOWING);
    });

    it('says the fan-in once in words, visually hidden, for a screen reader', () => {
        const sentences = Array.from(rail().querySelectorAll('.rail-fan-in'));

        expect(sentences).toHaveLength(1);
        expect(sentences[0].classList.contains('sr-only')).toBe(true);
        expect(sentences[0].textContent?.trim()).toBe('2 sources, merged at Deduplicate');
    });

    it('keeps every link in run order, the unread entry last, with one aria-current', () => {
        const links = Array.from(rail('SCORE').querySelectorAll('nav a'));

        expect(links.map((a) => a.getAttribute('data-stage'))).toEqual([...ALL.map((s) => s.id), 'unread']);
        expect(links.filter((a) => a.getAttribute('aria-current') === 'page').map((a) => a.getAttribute('data-stage'))).toEqual(['SCORE']);
    });

    it('draws every entry as a compact pill, not a full-width row', () => {
        expect(rail().querySelectorAll('a.stage-pill[data-stage]')).toHaveLength(STAGE_COUNT + 1);
    });
});

describe('StageRail draws the running pass (ISC-417)', () => {
    beforeEach(() => {
        TestBed.configureTestingModule({providers: [provideRouter([])]});
    });

    function currentRun(stageId: string, stageStartedAt: string | null): CurrentRunView {
        return {
            id: 1,
            startedAt: '2026-09-24T09:50:00Z',
            scoreModel: 'gpt-oss:20b',
            stage: stageId,
            stagePosition: null,
            stageTotal: null,
            stageStartedAt,
        };
    }

    // The 13-stage run order of WORKFLOW (the two sources, then DEDUPE through DIGEST): CONTENT
    // sits sixth, the middle stop, with everything through ENRICH already done and everything
    // from FIELDS on still pending.
    const MID_RUN = runState(WORKFLOW, currentRun('CONTENT', '2026-09-24T09:58:25Z'));
    const DONE = ['INGEST zeta', 'INGEST alpha', 'DEDUPE', 'FILTER', 'ARCHIVE', 'ENRICH'];
    const PENDING = ['FIELDS', 'SCORE', 'RETRIEVAL', 'OPEN', 'PACKAGE', 'DIGEST'];

    function render(run: RunState | null, elapsed: number | null = null, counts: Record<string, string | number | null> = {}): ComponentFixture<StageRail> {
        const fixture = TestBed.createComponent(StageRail);
        fixture.componentRef.setInput('workflow', WORKFLOW);
        fixture.componentRef.setInput('selected', 'CONTENT');
        fixture.componentRef.setInput('counts', counts);
        fixture.componentRef.setInput('run', run);
        fixture.componentRef.setInput('elapsed', elapsed);
        fixture.detectChanges();
        return fixture;
    }

    function element(fixture: ComponentFixture<StageRail>): HTMLElement {
        return fixture.nativeElement as HTMLElement;
    }

    function rowOf(rail: HTMLElement, id: string): Element | null {
        return rail.querySelector(`[data-stage="${id}"]`)?.closest('.stage-row') ?? null;
    }

    it('draws the spine solid down to the running stop and dashed below it', () => {
        const rail = element(render(MID_RUN));

        for (const id of DONE) {
            expect(rowOf(rail, id)?.classList.contains('is-run-pending')).toBe(false);
        }
        expect(rowOf(rail, 'CONTENT')?.classList.contains('is-run-running')).toBe(true);
        expect(rowOf(rail, 'CONTENT')?.classList.contains('is-run-pending')).toBe(false);
        for (const id of PENDING) {
            expect(rowOf(rail, id)?.classList.contains('is-run-pending')).toBe(true);
        }
        // A phase the run has not entered yet dashes its own heading, the same line continued;
        // one it has already entered (even mid-way through, as here) keeps it solid.
        expect(rail.querySelector('#rail-phase-judge')?.classList.contains('is-run-pending')).toBe(true);
        expect(rail.querySelector('#rail-phase-understand')?.classList.contains('is-run-pending')).toBe(false);
    });

    it('carries the running stop in the run colour with its elapsed time, and no chip', () => {
        const rail = element(render(MID_RUN, 95));
        const link = rail.querySelector('a[data-stage="CONTENT"]');
        const elapsed = link?.querySelector('.stage-elapsed');

        expect(link?.closest('.stage-row')?.classList.contains('is-run-running')).toBe(true);
        expect(elapsed?.textContent?.trim()).toBe('1:35');
        expect(elapsed?.getAttribute('aria-hidden')).toBe('true');
        expect(link?.querySelector('.stage-count')).toBeNull();
    });

    it('hides every last-run chip while a pass is placed, not only the running stop', () => {
        const rail = element(render(MID_RUN, 95, {FILTER: 12548, SCORE: 12537}));

        expect(rail.querySelector('a[data-stage="FILTER"] .stage-count')).toBeNull();
        expect(rail.querySelector('a[data-stage="SCORE"] .stage-count')).toBeNull();
    });

    it('looks exactly as it does today with no run reported', () => {
        const rail = element(render(null, null, {SCORE: 12537}));

        expect(rail.querySelectorAll('.is-run-pending, .is-run-running')).toHaveLength(0);
        expect(rail.querySelector('.stage-elapsed')).toBeNull();
        expect(rail.querySelector('a[data-stage="SCORE"] .stage-count')?.textContent?.trim()).toBe('12,537 scored');
    });
});
