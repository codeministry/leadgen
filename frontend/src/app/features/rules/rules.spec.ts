import {Location} from '@angular/common';
import {provideHttpClient} from '@angular/common/http';
import {HttpTestingController, provideHttpClientTesting} from '@angular/common/http/testing';
import {provideLocationMocks} from '@angular/common/testing';
import {ComponentFixture, TestBed} from '@angular/core/testing';
import {NavigationEnd, provideRouter, Router, withComponentInputBinding} from '@angular/router';
import {RouterTestingHarness} from '@angular/router/testing';
import {FunnelView} from '@core/model/funnel';
import {LastRunView} from '@core/model/last-run';
import {PromptView} from '@core/model/prompt-view';
import {filter, firstValueFrom} from 'rxjs';
import {RulesView} from '@core/model/rules-view';
import {WorkflowStage, WorkflowView} from '@core/model/workflow';
import {Rules} from './rules';

const RULES: RulesView = {
    version: '2026-09-01',
    weights: [{key: 'skill', points: 40}],
    penalties: [{key: 'onsite', points: -15}],
    thresholds: {autoShortlist: 70, review: 50, discard: 30},
    archiveAfterDays: 21,
    knockouts: [{key: 'abroad', label: 'Abroad', value: 'DE', values: []}],
    interestTopics: [{name: 'kotlin', weight: 5}],
    disinterestTopics: [{name: 'sap', weight: -10}],
};

const PROMPTS: readonly PromptView[] = [
    {id: 'scoring', model: 'judge-model', system: 'Score this offer.', user: 'OFFER {title}'},
    {id: 'content', model: 'label-model', system: 'Label every block.', user: 'BLOCKS {blocks}'},
    {id: 'fields', model: 'label-model', system: 'Read the dates.', user: 'ADVERT {advert}'},
];

/** Six filter stages, as the server sends them; the survivors are stated, not derived. */
const FUNNEL: FunnelView = {
    total: 100,
    stages: [
        {id: 'remote', label: 'Remote', removed: 10},
        {id: 'abroad', label: 'Abroad', removed: 6},
        {id: 'rate', label: 'Rate', removed: 4},
        {id: 'stack', label: 'Stack', removed: 20},
        {id: 'excluded', label: 'Excluded', removed: 5},
        {id: 'duplicate', label: 'Duplicate', removed: 5},
    ],
    survived: 50,
    archived: 3,
};

function stage(
    id: string,
    costClasses: readonly string[],
    sourceId: string | null = null,
    extra: Partial<WorkflowStage> = {},
): WorkflowStage {
    return {
        id,
        kind: sourceId === null ? 'stage' : 'ingest',
        sourceId,
        description: `What ${id} does.`,
        costClasses,
        promptId: null,
        settings: [],
        knockouts: null,
        ...extra,
    };
}

/** Five phases in the server's order; the two ingest entries are deliberately not alphabetical. */
const WORKFLOW: WorkflowView = {
    phases: [
        {id: 'read', stages: [stage('INGEST zeta', ['network', 'model'], 'zeta'), stage('INGEST alpha', ['file'], 'alpha')]},
        {
            id: 'sort',
            stages: [
                stage('DEDUPE', ['free']),
                stage('FILTER', ['free'], null, {
                    settings: [{key: 'remote.accept_unknown', value: 'true', file: 'matching-rules.yaml'}],
                    knockouts: [{id: 'remote', description: 'Drops on-site offers.', keys: ['remote.accept_unknown']}],
                }),
                stage('ARCHIVE', ['free']),
            ],
        },
        {
            id: 'understand',
            stages: [stage('ENRICH', ['network']), stage('CONTENT', ['model'], null, {promptId: 'content'}), stage('FIELDS', ['model'], null, {promptId: 'fields'})],
        },
        {id: 'judge', stages: [stage('SCORE', ['model'], null, {promptId: 'scoring'}), stage('RETRIEVAL', ['model'])]},
        {id: 'hand', stages: [stage('OPEN', ['free']), stage('PACKAGE', ['file']), stage('DIGEST', ['file'])]},
    ],
    unread: [{key: 'legacy.flag', value: 'true', file: 'pipeline.yaml'}],
};

const STAGES = WORKFLOW.phases.flatMap((phase) => phase.stages);

const LAST_RUN: LastRunView = {
    finishedAt: '2026-09-24T04:13:00Z',
    status: 'COMPLETE',
    scoreModel: null,
    extracted: 120,
    written: 80,
    merged: 12,
    enriched: 25,
    removed: {abroad: 6},
    filterConsidered: 80,
    filterPassed: 30,
    scored: 30,
    shortlisted: 4,
    review: 6,
    packaged: 0,
    digestWritten: true,
    sources: [{sourceId: 'zeta', documents: 1, extracted: 57, written: 50, announced: null, complete: true}],
    stages: [],
};

/** Answers every request the screen and the stores it injects make; returns the URLs asked. */
function answer(
    http: HttpTestingController,
    workflow: WorkflowView = WORKFLOW,
    rulesFail = false,
    lastRun: LastRunView | null = LAST_RUN,
): string[] {
    const urls: string[] = [];
    for (const request of http.match(() => true)) {
        const url = request.request.url;
        urls.push(url);
        // The ingest store asks for the last run when it is created and the screen asks
        // again on open; `switchMap` cancels the first.
        if (request.cancelled) {
            continue;
        }
        if (url === '/api/v1/rules' && rulesFail) {
            request.flush(null, {status: 500, statusText: 'Server Error'});
        } else if (url === '/api/v1/rules') {
            request.flush(RULES);
        } else if (url === '/api/v1/prompts') {
            request.flush(PROMPTS);
        } else if (url === '/api/v1/workflow') {
            request.flush(workflow);
        } else if (url === '/api/v1/ingest/last' && lastRun === null) {
            request.flush(null, {status: 204, statusText: 'No Content'});
        } else if (url === '/api/v1/ingest/last') {
            request.flush(lastRun);
        } else if (url === '/api/v1/offers/funnel') {
            request.flush(FUNNEL);
        } else if (url === '/api/v1/scoring-models') {
            request.flush({default: null, available: []});
        } else {
            request.flush(null, {status: 204, statusText: 'No Content'});
        }
    }
    return urls;
}

/**
 * The screen shell: the rail on the left, the selected stage on the right.
 *
 * <p>What the rail itself draws is `stage-rail.spec.ts`, what the detail pane draws per stage
 * is `stage-detail.spec.ts`; this spec proves the screen feeds both from the server, reads the
 * selection from the `stage` query parameter, and no longer carries spec 007's anchor rail.
 */
describe('Rules', () => {
    let http: HttpTestingController;

    beforeEach(() => {
        TestBed.configureTestingModule({
            providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
        });
        http = TestBed.inject(HttpTestingController);
    });

    function render(
        workflow: WorkflowView = WORKFLOW,
        rulesFail = false,
        selected?: string,
        lastRun: LastRunView | null = LAST_RUN,
    ): {fixture: ComponentFixture<Rules>; urls: string[]} {
        const fixture = TestBed.createComponent(Rules);
        if (selected !== undefined) {
            fixture.componentRef.setInput('stage', selected);
        }
        fixture.detectChanges();
        const urls = answer(http, workflow, rulesFail, lastRun);
        fixture.detectChanges();
        return {fixture, urls};
    }

    function element(fixture: ComponentFixture<Rules>): HTMLElement {
        return fixture.nativeElement as HTMLElement;
    }

    it('asks for the workflow, the last run and the funnel the rail reads', () => {
        const {urls} = render();

        expect(urls).toContain('/api/v1/workflow');
        expect(urls).toContain('/api/v1/ingest/last');
        expect(urls).toContain('/api/v1/offers/funnel');
    });

    it('renders the stage rail with the five phases in the order the server sent them', () => {
        const page = element(render().fixture);
        const rail = page.querySelector('lg-stage-rail');

        expect(rail).not.toBeNull();
        const headings = Array.from(rail!.querySelectorAll('.phase-title'), (h) => h.textContent?.trim());
        expect(headings).toEqual(['Read', 'Sort', 'Understand', 'Judge', 'Hand over']);
    });

    it('lists every stage in server order with the unread entry last', () => {
        const links = Array.from(element(render().fixture).querySelectorAll('lg-stage-rail nav a'));

        expect(links.map((a) => a.getAttribute('data-stage'))).toEqual([...STAGES.map((s) => s.id), 'unread']);
        expect(links.at(-1)?.querySelector('.stage-name')?.textContent?.trim()).toBe('Read by nothing');
    });

    it('marks every cost class of every stage with an icon that has an accessible name', () => {
        const page = element(render().fixture);
        const links = Array.from(page.querySelectorAll('lg-stage-rail nav a'));

        STAGES.forEach((s, index) => {
            const names = Array.from(links[index].querySelectorAll('.stage-costs svg[role="img"]'), (svg) =>
                svg.getAttribute('aria-label'),
            );
            expect(names, s.id).toHaveLength(s.costClasses.length);
            for (const name of names) {
                expect(name, s.id).toBeTruthy();
            }
        });
    });

    it('selects the first stage and names it in a labelled detail region', () => {
        const page = element(render().fixture);
        const current = page.querySelectorAll('lg-stage-rail [aria-current="page"]');
        const detail = page.querySelector('section.detail-pane');

        expect(current).toHaveLength(1);
        expect(current[0].getAttribute('data-stage')).toBe('INGEST zeta');
        expect(detail?.getAttribute('aria-label')).toBe('Selected stage');
        expect(detail?.textContent).toContain('zeta');
    });

    it('shows in the rail only the counts the last run itself left there', () => {
        const page = element(render().fixture);
        const countOf = (id: string): string | undefined =>
            page.querySelector(`lg-stage-rail a[data-stage="${id}"] .stage-count`)?.textContent?.trim();

        expect(countOf('INGEST zeta')).toBe(String(LAST_RUN.sources[0].extracted));
        expect(countOf('FILTER')).toBe(String(LAST_RUN.removed['abroad']));
        expect(countOf('ENRICH')).toBe(String(LAST_RUN.enriched));
        expect(countOf('SCORE')).toBe(String(LAST_RUN.scored));
        expect(countOf('PACKAGE')).toBe(String(LAST_RUN.packaged));
        // Reported by no source of the run: no number, never a zero.
        expect(countOf('INGEST alpha')).toBeUndefined();
        // Standing totals, not this run's own figures (spec 008 § Decisions): no count.
        expect(countOf('DEDUPE')).toBeUndefined();
        expect(countOf('OPEN')).toBeUndefined();
        expect(countOf('ARCHIVE')).toBeUndefined();
    });

    it('says in words that no run has finished, and draws no counts, before the first run', () => {
        const rail = element(render(WORKFLOW, false, undefined, null).fixture).querySelector('lg-stage-rail');

        expect(rail?.querySelector('.no-run-note')?.textContent?.trim()).toBe(
            'No run yet — the stages carry no counts until one finishes.',
        );
        expect(rail?.querySelectorAll('.stage-count').length).toBe(0);
    });

    it('keeps a failed rules request visible beside a loaded workflow', () => {
        const page = element(render(WORKFLOW, true).fixture);
        const alert = page.querySelector('[role="alert"]');

        expect(page.querySelector('lg-stage-rail')).not.toBeNull();
        expect(alert).not.toBeNull();
        expect(alert?.textContent?.trim()).toBeTruthy();
        expect(alert?.textContent).not.toContain('error.rulesLoad');
    });

    it('carries no anchor rail and offers no primary action', () => {
        const page = element(render().fixture);

        expect(page.querySelector('lg-anchor-rail')).toBeNull();
        expect(page.querySelector('.btn-primary')).toBeNull();
    });

    // ISC-289: the detail pane is `lg-stage-detail`, fed the stage and the data it needs.

    it('draws FILTER through the funnel rail with six stage rows and the survivors last', () => {
        const detail = element(render(WORKFLOW, false, 'FILTER').fixture).querySelector('section.detail-pane lg-stage-detail');
        const rows = Array.from(detail?.querySelectorAll('lg-funnel-rail li.row') ?? []);

        expect(detail).not.toBeNull();
        expect(rows.filter((row) => !row.classList.contains('head') && !row.classList.contains('survived'))).toHaveLength(6);
        expect(rows.at(-1)?.classList.contains('survived')).toBe(true);
        expect(rows.at(-1)?.textContent).toContain('50');
    });

    it('shows SCORE with its weights, penalties, bands and topics', () => {
        const text = element(render(WORKFLOW, false, 'SCORE').fixture).querySelector('lg-stage-detail')?.textContent ?? '';

        for (const expected of ['skill', '40', 'onsite', '-15', '70', '< 50', 'kotlin', 'sap', 'Score this offer.']) {
            expect(text.replace(/\s+/g, ' '), expected).toContain(expected);
        }
    });

    it('shows CONTENT with the content prompt from /api/v1/prompts', () => {
        const detail = element(render(WORKFLOW, false, 'CONTENT').fixture).querySelector('lg-stage-detail');
        const blocks = Array.from(detail?.querySelectorAll('pre') ?? [], (pre) => pre.textContent?.trim());

        expect(blocks).toEqual(['Label every block.', 'BLOCKS {blocks}']);
        expect(detail?.textContent).toContain('label-model');
    });

    it('shows FIELDS with the fields prompt from /api/v1/prompts', () => {
        const detail = element(render(WORKFLOW, false, 'FIELDS').fixture).querySelector('lg-stage-detail');
        const blocks = Array.from(detail?.querySelectorAll('pre') ?? [], (pre) => pre.textContent?.trim());

        expect(blocks).toEqual(['Read the dates.', 'ADVERT {advert}']);
    });

    afterEach(() => http.verify());
});

/**
 * ISC-288: the selection is the `stage` query parameter, bound as a routed input, so a reload
 * reads it back and the back button restores it. Needs `withComponentInputBinding()` — without
 * it the input stays at its default and every one of these would show the first stage.
 */
describe('Rules — the stage query parameter', () => {
    let http: HttpTestingController;
    let harness: RouterTestingHarness | null;

    beforeEach(() => {
        TestBed.configureTestingModule({
            providers: [
                provideRouter([{path: 'rules', component: Rules}], withComponentInputBinding()),
                provideLocationMocks(),
                provideHttpClient(),
                provideHttpClientTesting(),
            ],
        });
        http = TestBed.inject(HttpTestingController);
        harness = null;
    });

    /** Created in the test rather than in `beforeEach`, the way `review.spec.ts` does it. */
    async function router(): Promise<RouterTestingHarness> {
        if (harness === null) {
            harness = await RouterTestingHarness.create();
            // An app does this in its initial navigation, which a test never runs; without it
            // the router does not hear the back button at all.
            TestBed.inject(Router).setUpLocationChangeListener();
        }
        return harness;
    }

    async function settle(): Promise<void> {
        const h = await router();
        answer(http);
        h.detectChanges();
        await h.fixture.whenStable();
        h.detectChanges();
    }

    async function open(url: string): Promise<void> {
        await (await router()).navigateByUrl(url);
        await settle();
    }

    function current(): string | null | undefined {
        const page = harness!.routeNativeElement as HTMLElement;
        const selected = page.querySelectorAll('lg-stage-rail [aria-current="page"]');
        expect(selected.length).toBeLessThanOrEqual(1);
        return selected[0]?.getAttribute('data-stage');
    }

    it('shows the first stage when the URL names none', async () => {
        await open('/rules');

        expect(current()).toBe('INGEST zeta');
    });

    it('shows the stage the URL names, matched case-insensitively', async () => {
        await open('/rules?stage=filter');

        expect(current()).toBe('FILTER');
        expect((harness!.routeNativeElement as HTMLElement).querySelector('lg-stage-detail lg-funnel-rail')).not.toBeNull();
    });

    it('falls back to the first stage for a value the server did not name', async () => {
        await open('/rules?stage=nope');

        expect(current()).toBe('INGEST zeta');
    });

    it('selects the unread entry by its own value', async () => {
        await open('/rules?stage=unread');

        expect(current()).toBe('unread');
    });

    it('restores the previous selection on back', async () => {
        await open('/rules');
        await open('/rules?stage=filter');
        await open('/rules?stage=nope');
        expect(current()).toBe('INGEST zeta');

        // The mock location moves at once; the router's popstate navigation finishes later.
        const navigated = firstValueFrom(
            TestBed.inject(Router).events.pipe(filter((event) => event instanceof NavigationEnd)),
        );
        TestBed.inject(Location).back();
        await navigated;
        await settle();

        expect(TestBed.inject(Location).path()).toBe('/rules?stage=filter');
        expect(current()).toBe('FILTER');
    });

    afterEach(() => http.verify());
});
