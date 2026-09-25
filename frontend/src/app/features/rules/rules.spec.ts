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
import {PIPE_BELOW_PX, Rules} from './rules';

/**
 * A ResizeObserver that reports one content width at once, so jsdom, which has neither layout nor
 * a reporting observer, can take either side of the breakpoint (ISC-395).
 */
function boxWidth(width: number): void {
    vi.stubGlobal(
        'ResizeObserver',
        class {
            private readonly callback: ResizeObserverCallback;
            constructor(callback: ResizeObserverCallback) {
                this.callback = callback;
            }
            observe(target: Element): void {
                this.callback([{target, contentRect: {width}} as unknown as ResizeObserverEntry], this as unknown as ResizeObserver);
            }
            unobserve(): void {
                // Nothing to stop: the width was reported once.
            }
            disconnect(): void {
                // As above.
            }
        },
    );
}

// The desktop canvas unless a test narrows the box; test-setup's observer never reports at all.
beforeEach(() => boxWidth(1440));
afterEach(() => vi.unstubAllGlobals());

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
    {
        id: 'scoring',
        model: 'judge-model',
        modelKey: 'llm.models.scoring',
        ownKey: 'llm.models.scoring',
        modelFallback: false,
        system: 'Score this offer.',
        user: 'OFFER {title}',
    },
    {
        id: 'content',
        model: 'label-model',
        modelKey: 'llm.models.content',
        ownKey: 'llm.models.content',
        modelFallback: false,
        system: 'Label every block.',
        user: 'BLOCKS {blocks}',
    },
    {
        id: 'fields',
        model: 'label-model',
        modelKey: 'llm.models.fields',
        ownKey: 'llm.models.fields',
        modelFallback: false,
        system: 'Read the dates.',
        user: 'ADVERT {advert}',
    },
    {
        id: 'writing',
        model: 'writer-model',
        modelKey: 'llm.models.writing',
        ownKey: 'llm.models.writing',
        modelFallback: false,
        system: 'Write one cover letter.',
        user: 'STYLE RULES {rules}',
    },
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
        width: null,
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
        {id: 'hand', stages: [stage('OPEN', ['free']), stage('PACKAGE', ['file'], null, {promptId: 'writing'}), stage('DIGEST', ['file'])]},
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
        boxWidth(375);
        const page = element(render().fixture);
        const rail = page.querySelector('lg-stage-rail');

        expect(rail).not.toBeNull();
        const headings = Array.from(rail!.querySelectorAll('.phase-title'), (h) => h.textContent?.trim());
        expect(headings).toEqual(['Read', 'Sort', 'Understand', 'Judge', 'Hand over']);
    });

    it('lists every stage in server order with the unread entry last', () => {
        boxWidth(375);
        const links = Array.from(element(render().fixture).querySelectorAll('lg-stage-rail nav a'));

        expect(links.map((a) => a.getAttribute('data-stage'))).toEqual([...STAGES.map((s) => s.id), 'unread']);
        expect(links.at(-1)?.querySelector('.stage-name')?.textContent?.trim()).toBe('Read by nothing');
    });

    it('marks every cost class of every stage with an icon that has an accessible name', () => {
        boxWidth(375);
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

    // ISC-393 replaces spec 008's "first stage when none": the graph is the page, a stage opens on demand.
    it('opens no sheet and selects nothing when no stage is named', () => {
        const page = element(render().fixture);

        expect(page.querySelector('lg-stage-sheet')).toBeNull();
        expect(page.querySelector('lg-stage-detail')).toBeNull();
        expect(page.querySelector('section.detail-pane')).toBeNull();
        expect(page.querySelectorAll('[aria-current="page"]')).toHaveLength(0);
        expect(page.querySelector('lg-flow-canvas')).not.toBeNull();
    });

    it('opens the named stage in a sheet at the window edge', () => {
        const page = element(render(WORKFLOW, false, 'INGEST zeta').fixture);
        const sheet = page.querySelector('lg-stage-sheet [role="dialog"]');

        expect(sheet).not.toBeNull();
        expect(sheet?.querySelector('lg-stage-detail')?.textContent).toContain('zeta');
    });

    // ISC-393, refined: the sheet belongs to the browser window's right edge, not the canvas's.
    it('places the sheet outside the canvas area, so nothing but the window bounds it', () => {
        const page = element(render(WORKFLOW, false, 'FILTER').fixture);

        expect(page.querySelector('lg-stage-sheet')).not.toBeNull();
        expect(page.querySelector('.canvas-area lg-stage-sheet')).toBeNull();
    });

    it('shows in the rail only the counts the last run itself left there', () => {
        boxWidth(375);
        const page = element(render().fixture);
        const countOf = (id: string): string | undefined =>
            page.querySelector(`lg-stage-rail a[data-stage="${id}"] .stage-count`)?.textContent?.trim();

        expect(countOf('INGEST zeta')).toBe(`${LAST_RUN.sources[0].extracted} read`);
        expect(countOf('FILTER')).toBe(`−${LAST_RUN.removed['abroad']} held back`);
        expect(countOf('ENRICH')).toBe(`${LAST_RUN.enriched} enriched`);
        expect(countOf('SCORE')).toBe(`${LAST_RUN.scored} scored`);
        expect(countOf('PACKAGE')).toBe(`${LAST_RUN.packaged} packaged`);
        // Reported by no source of the run: no number, never a zero.
        expect(countOf('INGEST alpha')).toBeUndefined();
        // Standing totals, not this run's own figures (spec 008 § Decisions): no count.
        expect(countOf('DEDUPE')).toBeUndefined();
        expect(countOf('OPEN')).toBeUndefined();
        expect(countOf('ARCHIVE')).toBeUndefined();
    });

    it('says in words that no run has finished, and draws no counts, before the first run', () => {
        boxWidth(375);
        const rail = element(render(WORKFLOW, false, undefined, null).fixture).querySelector('lg-stage-rail');

        expect(rail?.querySelector('.no-run-note')?.textContent?.trim()).toBe(
            'No run yet — the stages carry no counts until one finishes.',
        );
        expect(rail?.querySelectorAll('.stage-count').length).toBe(0);
    });

    it('keeps a failed rules request visible beside a loaded workflow', () => {
        boxWidth(375);
        const page = element(render(WORKFLOW, true).fixture);
        const alert = page.querySelector('[role="alert"]');

        expect(page.querySelector('lg-stage-rail')).not.toBeNull();
        expect(alert).not.toBeNull();
        expect(alert?.textContent?.trim()).toBeTruthy();
        expect(alert?.textContent).not.toContain('error.rulesLoad');
    });

    // ISC-395: one drawing of the workflow per width, never both, and vflow never below the breakpoint.
    it('draws the canvas with its legend beside it and no pipe from the breakpoint up', () => {
        boxWidth(PIPE_BELOW_PX);
        const page = element(render().fixture);

        expect(page.querySelector('.canvas-area lg-flow-canvas')).not.toBeNull();
        expect(page.querySelector('.canvas-area lg-flow-legend a[data-stage="unread"]')).not.toBeNull();
        expect(page.querySelector('lg-stage-rail')).toBeNull();
    });

    it('draws the pipe with the one legend under it and no canvas below the breakpoint', () => {
        boxWidth(PIPE_BELOW_PX - 1);
        const page = element(render().fixture);
        const rail = page.querySelector('lg-stage-rail');
        const legend = page.querySelector('lg-flow-legend');

        expect(page.querySelector('lg-flow-canvas')).toBeNull();
        expect(page.querySelector('vflow')).toBeNull();
        expect(rail).not.toBeNull();
        expect(rail?.compareDocumentPosition(legend as Node)).toBe(Node.DOCUMENT_POSITION_FOLLOWING);
        // The rail's own legend is gone: one implementation, the canvas's.
        expect(page.querySelector('.rail-legend')).toBeNull();
        // "Read by nothing" is the pipe's last entry, so the legend under it does not repeat the link.
        expect(page.querySelectorAll('a[data-stage="unread"]')).toHaveLength(1);
        expect(legend?.querySelector('a[data-stage]')).toBeNull();
    });

    it('leaves no icon on a pipe entry that the legend under it lacks', () => {
        boxWidth(375);
        const page = element(render().fixture);
        const legend = new Set(Array.from(page.querySelectorAll('lg-flow-legend [data-icon]'), (icon) => icon.getAttribute('data-icon')));
        const used = Array.from(page.querySelectorAll('lg-stage-rail a[data-stage] [data-icon]'), (icon) => icon.getAttribute('data-icon'));

        expect(used.length).toBeGreaterThan(0);
        expect(used.filter((name) => !legend.has(name))).toEqual([]);
    });

    // ISC-396: above the breakpoint the pipe's fan-in sentence is gone with it, so the screen says it.
    it('says the fan-in in words beside the canvas, and only there', () => {
        const page = element(render().fixture);
        const sentences = page.querySelectorAll('.rules-fan-in, .rail-fan-in');

        expect(sentences).toHaveLength(1);
        expect(sentences[0].closest('.canvas-area')).not.toBeNull();
        expect(sentences[0].classList.contains('sr-only')).toBe(true);
        expect(sentences[0].textContent?.trim()).toBe('2 sources, merged at Deduplicate');
    });

    it('carries no anchor rail and offers no primary action', () => {
        const page = element(render().fixture);

        expect(page.querySelector('lg-anchor-rail')).toBeNull();
        expect(page.querySelector('.btn-primary')).toBeNull();
    });

    // ISC-398: the canvas legend names every marker a node can carry, drawn as the node draws it.

    it('gives every marker a rendered node carries a legend entry with its icon and a label', () => {
        const every: WorkflowView = {
            ...WORKFLOW,
            phases: WORKFLOW.phases.map((phase) => ({
                ...phase,
                stages: phase.stages.map((s) => (s.id === 'ENRICH' ? {...s, width: {key: 'enrichment.fetch.concurrency', value: 4}} : s)),
            })),
        };
        const failedAt = {position: 3, stage: 'ENRICH', startedAt: '', endedAt: '', millis: 1, status: 'FAILED', note: null, width: null};
        const page = element(render(every, false, undefined, {...LAST_RUN, stages: [failedAt]}).fixture);
        const legend = page.querySelector('.canvas-area lg-flow-legend');
        expect(legend, 'legend inside the canvas area').not.toBeNull();

        const icons = new Set(Array.from(page.querySelectorAll('lg-flow-canvas lg-flow-node [data-icon]'), (el) => el.getAttribute('data-icon')));
        // The fixture draws every marker a node knows: four cost classes, AI, failed.
        expect(icons.size).toBe(6);
        for (const icon of icons) {
            const entry = legend!.querySelector(`[data-marker] lg-icon[data-icon="${icon}"]`)?.closest('[data-marker]');
            expect(entry, `legend entry for ${icon}`).toBeTruthy();
            expect(entry!.querySelector('.legend-label')?.textContent?.trim(), `label for ${icon}`).toBeTruthy();
        }

        expect(page.querySelector('lg-flow-canvas .flow-node-width')).not.toBeNull();
        const width = legend!.querySelector('[data-marker="width"]');
        expect(width?.querySelector('.flow-node-width')?.textContent?.trim()).toBeTruthy();
        expect(width?.querySelector('.legend-label')?.textContent?.trim()).toBeTruthy();
    });

    // ISC-405: hovering or focusing a node answers in the legend; leaving it restores the legend.

    function legendState(page: HTMLElement, scope: string): {active: string[]; faded: string[]} {
        const all = Array.from(page.querySelectorAll<HTMLElement>(`${scope} [data-marker-id]`));
        const ids = (cls: string): string[] => all.filter((e) => e.classList.contains(cls)).map((e) => e.dataset['markerId'] ?? '');
        return {active: ids('is-active'), faded: ids('is-faded')};
    }

    function stageLink(page: HTMLElement, host: string, stageId: string): HTMLElement {
        const link = Array.from(page.querySelectorAll<HTMLElement>(`${host} [data-stage]`)).find((a) => a.dataset['stage'] === stageId);
        expect(link, `${stageId} in ${host}`).toBeTruthy();
        return link!;
    }

    it("highlights the hovered or focused node's markers in the legend and restores it on leaving", () => {
        const {fixture} = render();
        const page = element(fixture);
        expect(legendState(page, '.canvas-area lg-flow-legend')).toEqual({active: [], faded: []});

        // A source that fetches and asks a model: network, model and the AI marker.
        const zeta = stageLink(page, 'lg-flow-canvas lg-flow-node', 'INGEST zeta');
        zeta.dispatchEvent(new MouseEvent('pointerover', {bubbles: true}));
        fixture.detectChanges();
        expect(legendState(page, '.canvas-area lg-flow-legend')).toEqual({active: ['model', 'network', 'ai'], faded: ['free', 'file', 'failed', 'width']});

        zeta.dispatchEvent(new MouseEvent('pointerout', {bubbles: true, relatedTarget: null}));
        fixture.detectChanges();
        expect(legendState(page, '.canvas-area lg-flow-legend')).toEqual({active: [], faded: []});

        // Keyboard focus answers the same way: DEDUPE only costs nothing.
        const dedupe = stageLink(page, 'lg-flow-canvas lg-flow-node', 'DEDUPE');
        dedupe.dispatchEvent(new FocusEvent('focusin', {bubbles: true}));
        fixture.detectChanges();
        expect(legendState(page, '.canvas-area lg-flow-legend')).toEqual({active: ['free'], faded: ['model', 'network', 'file', 'ai', 'failed', 'width']});
        for (const label of page.querySelectorAll('.canvas-area lg-flow-legend [data-marker-id] .legend-label')) {
            expect(label.textContent?.trim()).toBeTruthy();
        }

        dedupe.dispatchEvent(new FocusEvent('focusout', {bubbles: true, relatedTarget: null}));
        fixture.detectChanges();
        expect(legendState(page, '.canvas-area lg-flow-legend')).toEqual({active: [], faded: []});
    });

    it("marks a failed, stacked node's failed and width markers active", () => {
        const every: WorkflowView = {
            ...WORKFLOW,
            phases: WORKFLOW.phases.map((phase) => ({
                ...phase,
                stages: phase.stages.map((s) => (s.id === 'ENRICH' ? {...s, width: {key: 'enrichment.fetch.concurrency', value: 4}} : s)),
            })),
        };
        const failedAt = {position: 3, stage: 'ENRICH', startedAt: '', endedAt: '', millis: 1, status: 'FAILED', note: null, width: null};
        const {fixture} = render(every, false, undefined, {...LAST_RUN, stages: [failedAt]});
        const page = element(fixture);

        stageLink(page, 'lg-flow-canvas lg-flow-node', 'ENRICH').dispatchEvent(new MouseEvent('pointerover', {bubbles: true}));
        fixture.detectChanges();

        expect(legendState(page, '.canvas-area lg-flow-legend').active).toEqual(['network', 'failed', 'width']);
    });

    it('lets a pipe pill below the breakpoint answer in the legend under the pipe', () => {
        boxWidth(375);
        const {fixture} = render();
        const page = element(fixture);

        const pill = stageLink(page, 'lg-stage-rail', 'ENRICH');
        pill.dispatchEvent(new MouseEvent('pointerover', {bubbles: true}));
        fixture.detectChanges();
        expect(legendState(page, 'lg-flow-legend.pipe-legend').active).toEqual(['network']);

        pill.dispatchEvent(new MouseEvent('pointerout', {bubbles: true, relatedTarget: null}));
        fixture.detectChanges();
        expect(legendState(page, 'lg-flow-legend.pipe-legend')).toEqual({active: [], faded: []});
    });

    it('offers "read by nothing" in the legend, off the flow, as a link to the unread keys', () => {
        const page = element(render().fixture);

        const unread = page.querySelector<HTMLAnchorElement>('.canvas-area lg-flow-legend a[data-stage="unread"]');
        expect(unread).not.toBeNull();
        expect(unread!.closest('lg-flow-canvas')).toBeNull();
        expect(unread!.getAttribute('href')).toContain('stage=unread');
        expect(unread!.textContent?.trim()).toBeTruthy();
    });

    // ISC-289: the detail pane is `lg-stage-detail`, fed the stage and the data it needs.

    it('draws FILTER through the funnel rail with six stage rows and the survivors last', () => {
        const detail = element(render(WORKFLOW, false, 'FILTER').fixture).querySelector('lg-stage-sheet lg-stage-detail');
        const rows = Array.from(detail?.querySelectorAll('lg-funnel-rail li.row') ?? []);

        expect(detail).not.toBeNull();
        expect(rows.filter((row) => !row.classList.contains('head') && !row.classList.contains('survived'))).toHaveLength(6);
        expect(rows.at(-1)?.classList.contains('survived')).toBe(true);
        expect(rows.at(-1)?.textContent).toContain('50');
    });

    it('shows SCORE with its weights, penalties, bands and topics', () => {
        const text = element(render(WORKFLOW, false, 'SCORE').fixture).querySelector('lg-stage-sheet lg-stage-detail')?.textContent ?? '';

        for (const expected of ['skill', '40', 'onsite', '-15', '70', '< 50', 'kotlin', 'sap', 'Score this offer.']) {
            expect(text.replace(/\s+/g, ' '), expected).toContain(expected);
        }
    });

    it('shows CONTENT with the content prompt from /api/v1/prompts', () => {
        const detail = element(render(WORKFLOW, false, 'CONTENT').fixture).querySelector('lg-stage-sheet lg-stage-detail');
        const blocks = Array.from(detail?.querySelectorAll('pre') ?? [], (pre) => pre.textContent?.trim());

        expect(blocks).toEqual(['Label every block.', 'BLOCKS {blocks}']);
        expect(detail?.textContent).toContain('label-model');
    });

    it('shows FIELDS with the fields prompt from /api/v1/prompts', () => {
        const detail = element(render(WORKFLOW, false, 'FIELDS').fixture).querySelector('lg-stage-sheet lg-stage-detail');
        const blocks = Array.from(detail?.querySelectorAll('pre') ?? [], (pre) => pre.textContent?.trim());

        expect(blocks).toEqual(['Read the dates.', 'ADVERT {advert}']);
    });

    it('shows PACKAGE with the cover-letter writer prompt from /api/v1/prompts (ISC-326)', () => {
        const detail = element(render(WORKFLOW, false, 'PACKAGE').fixture).querySelector('lg-stage-sheet lg-stage-detail');
        const blocks = Array.from(detail?.querySelectorAll('pre') ?? [], (pre) => pre.textContent?.trim());

        expect(blocks).toEqual(['Write one cover letter.', 'STYLE RULES {rules}']);
        expect(detail?.querySelector('[data-section="prompt"] h3')?.textContent?.trim()).toBe('Cover-letter writer');
        expect(detail?.textContent).toContain('writer-model');
    });

    afterEach(() => http.verify());
});

/**
 * ISC-288: the selection is the `stage` query parameter, bound as a routed input, so a reload
 * reads it back and the back button restores it. Needs `withComponentInputBinding()` — without
 * it the input stays at its default and every one of these would open no sheet.
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
        // The canvas card or the legend's entry above the breakpoint, the pipe's link below it.
        const selected = page.querySelectorAll('[aria-current="page"]');
        expect(selected.length).toBeLessThanOrEqual(1);
        return selected[0]?.getAttribute('data-stage');
    }

    function page(): HTMLElement {
        return harness!.routeNativeElement as HTMLElement;
    }

    function sheet(): HTMLElement | null {
        return page().querySelector('lg-stage-sheet [role="dialog"]');
    }

    /** The canvas's own card link for a stage, not the rail's. */
    function node(id: string): HTMLAnchorElement {
        const link = page().querySelector<HTMLAnchorElement>(`lg-flow-canvas lg-flow-node a[data-stage="${id}"]`);
        expect(link, id).not.toBeNull();
        return link!;
    }

    /** Resolves on the next finished navigation, then lets the screen render it. */
    async function afterNavigation(act: () => void): Promise<void> {
        const navigated = firstValueFrom(TestBed.inject(Router).events.pipe(filter((event) => event instanceof NavigationEnd)));
        act();
        await navigated;
        await settle();
    }

    /** Focus only lands on an element that is in the document. */
    async function attached(url: string): Promise<void> {
        await open(url);
        document.body.appendChild(harness!.fixture.nativeElement as HTMLElement);
        await settle();
    }

    it('opens no sheet and selects nothing when the URL names no stage', async () => {
        await open('/rules');

        expect(current()).toBeUndefined();
        expect(sheet()).toBeNull();
    });

    it('opens the stage the URL names, matched case-insensitively', async () => {
        await open('/rules?stage=filter');

        expect(current()).toBe('FILTER');
        expect(sheet()?.querySelector('lg-stage-detail lg-funnel-rail')).not.toBeNull();
    });

    it('opens no sheet for a value the server did not name', async () => {
        await open('/rules?stage=nope');

        expect(current()).toBeUndefined();
        expect(sheet()).toBeNull();
    });

    it('selects the unread entry by its own value', async () => {
        await open('/rules?stage=unread');

        expect(current()).toBe('unread');
        expect(sheet()).not.toBeNull();
    });

    it('restores a reloaded ?stage=score with its blocks', async () => {
        await open('/rules?stage=score');

        const text = (sheet()?.textContent ?? '').replace(/\s+/g, ' ');
        for (const expected of ['skill', '40', 'onsite', '-15', 'kotlin', 'Score this offer.']) {
            expect(text, expected).toContain(expected);
        }
    });

    it('sets ?stage= and opens the sheet on a click on a canvas node, focus on its heading', async () => {
        await attached('/rules');

        await afterNavigation(() => node('FILTER').click());

        expect(TestBed.inject(Location).path()).toBe('/rules?stage=FILTER');
        expect(sheet()?.querySelector('lg-funnel-rail')).not.toBeNull();
        expect(document.activeElement).toBe(sheet()?.querySelector('.sheet-heading'));
    });

    it('opens the sheet from the keyboard: Enter on a focused node', async () => {
        await attached('/rules');
        const link = node('SCORE');
        link.focus();
        expect(document.activeElement).toBe(link);

        // jsdom has no activation behaviour; a browser turns an unhandled Enter on a link into a click.
        await afterNavigation(() => {
            if (link.dispatchEvent(new KeyboardEvent('keydown', {key: 'Enter', bubbles: true, cancelable: true}))) {
                link.click();
            }
        });

        expect(TestBed.inject(Location).path()).toBe('/rules?stage=SCORE');
        expect(sheet()).not.toBeNull();
        expect(document.activeElement).toBe(sheet()?.querySelector('.sheet-heading'));
    });

    it('closes on Escape: parameter removed, sheet gone, focus back on the node', async () => {
        await attached('/rules?stage=filter');
        expect(sheet()).not.toBeNull();

        await afterNavigation(() =>
            sheet()!.querySelector('.sheet-heading')!.dispatchEvent(new KeyboardEvent('keydown', {key: 'Escape', bubbles: true})),
        );

        expect(TestBed.inject(Location).path()).toBe('/rules');
        expect(sheet()).toBeNull();
        expect(document.activeElement).toBe(node('FILTER'));
    });

    it('closes by its button the same way', async () => {
        await attached('/rules?stage=SCORE');

        await afterNavigation(() => sheet()!.querySelector<HTMLButtonElement>('button[data-action="close"]')!.click());

        expect(TestBed.inject(Location).path()).toBe('/rules');
        expect(sheet()).toBeNull();
        expect(document.activeElement).toBe(node('SCORE'));
    });

    it('closes on Escape pressed anywhere on the screen, not only inside the sheet (ISC-403)', async () => {
        await attached('/rules?stage=filter');
        expect(sheet()).not.toBeNull();

        await afterNavigation(() => document.body.dispatchEvent(new KeyboardEvent('keydown', {key: 'Escape', bubbles: true})));

        expect(TestBed.inject(Location).path()).toBe('/rules');
        expect(sheet()).toBeNull();
        expect(document.activeElement).toBe(node('FILTER'));
    });

    it('leaves Escape to an open dialog that owns it (ISC-403)', async () => {
        await attached('/rules?stage=filter');
        const dialog = document.createElement('dialog');
        dialog.setAttribute('open', '');
        document.body.appendChild(dialog);
        try {
            dialog.dispatchEvent(new KeyboardEvent('keydown', {key: 'Escape', bubbles: true}));
            await settle();

            expect(TestBed.inject(Location).path()).toBe('/rules?stage=filter');
            expect(sheet()).not.toBeNull();
        } finally {
            dialog.remove();
        }
    });

    it('closes on a click outside the sheet and the canvas (ISC-403)', async () => {
        await attached('/rules?stage=SCORE');

        await afterNavigation(() => page().querySelector<HTMLElement>('lg-page-header')!.click());

        expect(TestBed.inject(Location).path()).toBe('/rules');
        expect(sheet()).toBeNull();
    });

    it('stays open on a click inside the sheet (ISC-403)', async () => {
        await attached('/rules?stage=SCORE');

        sheet()!.querySelector<HTMLElement>('lg-stage-detail')!.click();
        await settle();

        expect(TestBed.inject(Location).path()).toBe('/rules?stage=SCORE');
        expect(sheet()).not.toBeNull();
    });

    it('selects another node on a click on it rather than closing (ISC-403)', async () => {
        await attached('/rules?stage=filter');

        await afterNavigation(() => node('SCORE').click());
        await settle();

        expect(TestBed.inject(Location).path()).toBe('/rules?stage=SCORE');
        expect(sheet()).not.toBeNull();
    });

    it('opens the unread keys in the sheet from the legend entry (ISC-398)', async () => {
        await attached('/rules');
        const link = page().querySelector<HTMLAnchorElement>('lg-flow-legend a[data-stage="unread"]');
        expect(link).not.toBeNull();

        await afterNavigation(() => link!.click());

        expect(TestBed.inject(Location).path()).toBe('/rules?stage=unread');
        // The settings block groups a dotted key by its segments, so `legacy.flag` reads as two.
        const text = sheet()?.textContent ?? '';
        for (const expected of ['pipeline.yaml', 'legacy', 'flag']) {
            expect(text, expected).toContain(expected);
        }
    });

    it('restores the previous selection on back', async () => {
        await open('/rules');
        await open('/rules?stage=filter');
        await open('/rules?stage=nope');
        expect(sheet()).toBeNull();

        // The mock location moves at once; the router's popstate navigation finishes later.
        await afterNavigation(() => TestBed.inject(Location).back());

        expect(TestBed.inject(Location).path()).toBe('/rules?stage=filter');
        expect(current()).toBe('FILTER');
        expect(sheet()?.querySelector('lg-funnel-rail')).not.toBeNull();
    });

    it('closes the sheet again on back after a close', async () => {
        await open('/rules?stage=filter');
        await afterNavigation(() => sheet()!.querySelector<HTMLButtonElement>('button[data-action="close"]')!.click());
        expect(sheet()).toBeNull();

        await afterNavigation(() => TestBed.inject(Location).back());

        expect(TestBed.inject(Location).path()).toBe('/rules?stage=filter');
        expect(sheet()).not.toBeNull();
    });

    afterEach(() => {
        (harness?.fixture.nativeElement as HTMLElement | undefined)?.remove();
        http.verify();
    });
});
