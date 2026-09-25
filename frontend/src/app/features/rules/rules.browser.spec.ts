import {Location} from '@angular/common';
import {provideHttpClient} from '@angular/common/http';
import {HttpTestingController, provideHttpClientTesting} from '@angular/common/http/testing';
import {provideLocationMocks} from '@angular/common/testing';
import {TestBed} from '@angular/core/testing';
import {provideRouter, withComponentInputBinding} from '@angular/router';
import {RouterTestingHarness} from '@angular/router/testing';
import {page, userEvent} from 'vitest/browser';
import {PromptView} from '@core/model/prompt-view';
import {RulesView} from '@core/model/rules-view';
import {WorkflowStage, WorkflowView} from '@core/model/workflow';
import {Rules} from './rules';
import {markersOf} from './stage-marks';

function stage(id: string, sourceId: string | null = null, costClasses: readonly string[] = ['free']): WorkflowStage {
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

const WORKFLOW: WorkflowView = {
    phases: [
        {id: 'read', stages: [stage('INGEST zeta', 'zeta', ['network', 'model']), stage('INGEST alpha', 'alpha')]},
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

/**
 * The canvas gives way to the pipe below the measured breakpoint (ISC-395), and at every width the
 * stages are one path of links in run order that a screen reader walks without the drawing
 * (ISC-396). jsdom has no layout, so the widths and the page overflow live here.
 */
describe('Rules — canvas or pipe by width (ISC-395, ISC-396)', () => {
    let http: HttpTestingController;
    let harness: RouterTestingHarness;

    const RUN_ORDER = [...WORKFLOW.phases.flatMap((phase) => phase.stages.map((s) => s.id)), 'unread'];

    async function mount(width: number, url: string): Promise<void> {
        await page.viewport(width, 900);
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
        await harness.navigateByUrl(url);
        for (let round = 0; round < 3; round++) {
            for (const request of http.match(() => true)) {
                if (request.cancelled) continue;
                if (request.request.url === '/api/v1/workflow') request.flush(WORKFLOW);
                else if (request.request.url === '/api/v1/scoring-models') request.flush({default: null, available: []});
                else request.flush(null, {status: 204, statusText: 'No Content'});
            }
            harness.detectChanges();
            await harness.fixture.whenStable();
            await frames(4);
        }
    }

    afterEach(() => {
        (harness.fixture.nativeElement as HTMLElement).remove();
    });

    function rules(): HTMLElement {
        return document.querySelector('lg-rules')!;
    }

    /** Every stage link on the screen in DOM order, the sheet's own content left out. */
    function path(): string[] {
        return Array.from(rules().querySelectorAll<HTMLAnchorElement>('a[data-stage]'))
            .filter((a) => a.closest('lg-stage-sheet') === null)
            .map((a) => a.dataset['stage']!);
    }

    function current(): string[] {
        return Array.from(rules().querySelectorAll<HTMLElement>('[aria-current="page"]'))
            .filter((el) => el.closest('lg-stage-sheet') === null)
            .map((el) => el.dataset['stage'] ?? '?');
    }

    function noSidewaysScroll(): void {
        expect(document.documentElement.scrollWidth).toBeLessThanOrEqual(document.documentElement.clientWidth);
    }

    it.each([1440, 720])('draws the canvas and not the pipe at %i px, its links in run order', async (width) => {
        await mount(width, '/rules?stage=FILTER');

        expect(rules().querySelector('lg-flow-canvas')).not.toBeNull();
        expect(rules().querySelector('lg-stage-rail')).toBeNull();
        expect(path()).toEqual(RUN_ORDER);
        expect(current()).toEqual(['FILTER']);
        noSidewaysScroll();
    });

    it('hides the edges and the handles from assistive technology at 1440 px', async () => {
        await mount(1440, '/rules');
        const edges = Array.from(rules().querySelectorAll('lg-flow-canvas .flow-edge'));
        const handles = Array.from(rules().querySelectorAll('lg-flow-canvas [data-handle]'));

        expect(edges.length).toBeGreaterThan(0);
        expect(handles.length).toBeGreaterThan(0);
        for (const decoration of [...edges, ...handles]) {
            expect(decoration.closest('[aria-hidden="true"]'), decoration.outerHTML.slice(0, 80)).not.toBeNull();
        }
    });

    it('says the fan-in into DEDUPE in words beside the canvas at 1440 px', async () => {
        await mount(1440, '/rules');
        const sentence = rules().querySelector('.canvas-area .rules-fan-in');

        expect(sentence?.textContent?.trim()).toBe('2 sources, merged at Deduplicate');
        noSidewaysScroll();
    });

    // ISC-405: a real pointer and real keyboard focus on a node answer in the legend.
    it('highlights a hovered or focused node\'s markers in the legend, fades the rest, and restores it', async () => {
        await mount(1440, '/rules');
        const stages = WORKFLOW.phases.flatMap((phase) => phase.stages);
        const legend = (): {active: string[]; faded: HTMLElement[]} => {
            const all = Array.from(rules().querySelectorAll<HTMLElement>('.canvas-area lg-flow-legend [data-marker-id]'));
            return {
                active: all.filter((e) => e.classList.contains('is-active')).map((e) => e.dataset['markerId']!),
                faded: all.filter((e) => e.classList.contains('is-faded')),
            };
        };
        const node = (id: string): HTMLAnchorElement =>
            Array.from(rules().querySelectorAll<HTMLAnchorElement>('lg-flow-node a[data-stage]')).find((a) => a.dataset['stage'] === id)!;

        const ai = stages.find((s) => markersOf(s, false).has('ai'))!;
        await userEvent.hover(node(ai.id));
        harness.detectChanges();
        await frames(12);
        expect(new Set(legend().active)).toEqual(markersOf(ai, false));
        expect(legend().faded.length).toBeGreaterThan(0);
        for (const faded of legend().faded) {
            expect(Number(getComputedStyle(faded).opacity)).toBeLessThan(0.5);
            expect(faded.querySelector('.legend-label')?.textContent?.trim()).toBeTruthy();
        }

        await userEvent.unhover(node(ai.id));
        harness.detectChanges();
        expect(legend()).toEqual({active: [], faded: []});

        const free = stages.find((s) => s.costClasses.length === 1 && s.costClasses[0] === 'free')!;
        node(free.id).focus({preventScroll: true});
        harness.detectChanges();
        expect(legend().active).toEqual(['free']);

        node(free.id).blur();
        harness.detectChanges();
        expect(legend()).toEqual({active: [], faded: []});
    });

    it.each([700, 375, 320])('renders the pipe and never the canvas at %i px', async (width) => {
        await mount(width, '/rules?stage=DEDUPE');

        expect(document.querySelector('lg-flow-canvas')).toBeNull();
        expect(document.querySelector('vflow')).toBeNull();
        expect(rules().querySelector('lg-stage-rail')).not.toBeNull();
        expect(path()).toEqual(RUN_ORDER);
        expect(current()).toEqual(['DEDUPE']);
        // The fan-in is said once, by the pipe.
        expect(rules().querySelectorAll('.rail-fan-in, .rules-fan-in')).toHaveLength(1);
        noSidewaysScroll();
    });

    it.each([375, 320])('opens the sheet across the full width at %i px without scrolling sideways', async (width) => {
        await mount(width, '/rules?stage=FILTER');
        const sheet = document.querySelector('lg-stage-sheet [role="dialog"]')!.getBoundingClientRect();

        expect(sheet.left).toBeLessThanOrEqual(0.5);
        expect(sheet.right).toBeGreaterThanOrEqual(document.documentElement.clientWidth - 0.5);
        noSidewaysScroll();
    });
});

/**
 * A sub-node opens its stage's sheet at its own section (ISC-406), and the fullscreen control puts
 * canvas, legend and sheet into the full screen and back (ISC-407). Against a real layout, since
 * "at the top of the sheet" and "fills the viewport" are geometry jsdom does not have.
 */
describe('Rules — sub-node sections and fullscreen (ISC-406, ISC-407)', () => {
    const many = (prefix: string) => Array.from({length: 40}, (_, i) => ({key: `${prefix}.key${i}`, value: 'x', file: 'pipeline.yaml'}));
    const OPEN_WORKFLOW: WorkflowView = {
        ...WORKFLOW,
        phases: WORKFLOW.phases.map((phase) => ({
            ...phase,
            stages: phase.stages.map((s): WorkflowStage => {
                if (s.id === 'FILTER') {
                    const knockouts = ['remote', 'rate', 'language'].map((id) => ({id, description: `Drops by ${id}.`, keys: []}));
                    return {...s, knockouts, settings: many('filter')};
                }
                return s.id === 'SCORE' ? {...s, promptId: 'scoring', costClasses: ['model'], settings: many('score')} : s;
            }),
        })),
    };
    const RULES: RulesView = {
        version: 'v1',
        weights: [{key: 'skill', points: 40}],
        penalties: [{key: 'onsite', points: -15}],
        thresholds: {autoShortlist: 70, review: 50, discard: 30},
        archiveAfterDays: 21,
        knockouts: [],
        interestTopics: [{name: 'kotlin', weight: 5}],
        disinterestTopics: [],
    };
    const PROMPTS: readonly PromptView[] = [
        {id: 'scoring', model: 'judge', modelKey: 'llm.models.scoring', ownKey: 'llm.models.scoring', modelFallback: false, system: 'S', user: 'U'},
    ];

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
        for (let round = 0; round < 2; round++) {
            for (const request of http.match(() => true)) {
                if (request.cancelled) continue;
                const url = request.request.url;
                if (url === '/api/v1/workflow') request.flush(OPEN_WORKFLOW);
                else if (url === '/api/v1/rules') request.flush(RULES);
                else if (url === '/api/v1/prompts') request.flush(PROMPTS);
                else if (url === '/api/v1/scoring-models') request.flush({default: null, available: []});
                else request.flush(null, {status: 204, statusText: 'No Content'});
            }
            harness.detectChanges();
            await harness.fixture.whenStable();
            await frames(4);
            harness.detectChanges();
            await harness.fixture.whenStable();
            await frames(2);
        }
    }

    const params = (): URLSearchParams => {
        const path = TestBed.inject(Location).path();
        return new URLSearchParams(path.includes('?') ? path.slice(path.indexOf('?') + 1) : '');
    };

    async function open(url: string): Promise<void> {
        await harness.navigateByUrl(url);
        await settle();
    }

    /** Expands the stage unless its sub-node is already drawn, then follows the sub-node's link; returns its icon. */
    async function follow(stageId: string, nodeId: string): Promise<string | undefined> {
        if (document.querySelector(`[data-node="${nodeId}"]`) === null) {
            document.querySelector<HTMLButtonElement>(`[data-node="stage:${stageId}"] button[data-action="expand"]`)!.click();
            await settle();
        }
        const link = document.querySelector<HTMLAnchorElement>(`[data-node="${nodeId}"] a[href]`);
        expect(link, nodeId).not.toBeNull();
        const icon = link!.querySelector<HTMLElement>('[data-icon]')?.dataset['icon'];
        link!.click();
        await settle();
        return icon;
    }

    /** The sheet shows the section at the top of its own scroll box, headed by `icon`, and the page never scrolled. */
    function expectAtTop(section: string, icon: string | undefined): void {
        const body = document.querySelector<HTMLElement>('lg-stage-sheet .sheet-body');
        expect(body, section).not.toBeNull();
        const target = Array.from(body!.querySelectorAll<HTMLElement>('[data-sub]')).find((el) => el.dataset['sub'] === section);
        expect(target, section).toBeDefined();
        expect(Math.abs(target!.getBoundingClientRect().top - body!.getBoundingClientRect().top), section).toBeLessThan(2);
        expect(body!.scrollTop, section).toBeGreaterThan(0);
        expect(target!.closest('[data-section]')?.querySelector<HTMLElement>('h3 [data-icon]')?.dataset['icon'], section).toBe(icon);
        expect(window.scrollY).toBe(0);
    }

    it('opens the parent stage\'s sheet at the knockout, block or prompt a sub-node names, and a reload restores it', async () => {
        await open('/rules');

        const cases = [
            {stage: 'FILTER', node: 'knockout:rate'},
            {stage: 'SCORE', node: 'score:bands'},
            {stage: 'SCORE', node: 'prompt:scoring'},
        ];
        for (const {stage: stageId, node} of cases) {
            const icon = await follow(stageId, node);
            expect(icon, node).toBeDefined();
            expect(params().get('stage'), node).toBe(stageId);
            expect(params().get('section'), node).toBe(node);
            // following the link opened nothing and closed nothing on the canvas
            expect(document.querySelector(`[data-node="${node}"]`), node).not.toBeNull();
            expectAtTop(node, icon);

            const url = TestBed.inject(Location).path();
            await open('/rules');
            await open(url);
            expectAtTop(node, icon);
        }

        // closing the sheet clears the section with the stage
        document.querySelector<HTMLButtonElement>('lg-stage-sheet button[data-action="close"]')!.click();
        await settle();
        expect(params().has('stage')).toBe(false);
        expect(params().has('section')).toBe(false);
    });

    describe('fullscreen (ISC-407)', () => {
        /*
         * Stubbed: Vitest runs each spec inside an iframe without `allowfullscreen`, where
         * `requestFullscreen` rejects, so the API is replaced by one that records the element and
         * fires `fullscreenchange` the way the browser does. The screen's reaction is what is tested.
         */
        let current: Element | null = null;
        const change = (): void => void document.dispatchEvent(new Event('fullscreenchange'));

        beforeEach(() => {
            current = null;
            Object.defineProperty(document, 'fullscreenEnabled', {configurable: true, get: () => true});
            Object.defineProperty(document, 'fullscreenElement', {configurable: true, get: () => current});
            vi.spyOn(Element.prototype, 'requestFullscreen').mockImplementation(function (this: Element) {
                // eslint-disable-next-line @typescript-eslint/no-this-alias -- the stub records which element asked, as the browser does
                current = this;
                queueMicrotask(change);
                return Promise.resolve();
            });
            vi.spyOn(document, 'exitFullscreen').mockImplementation(() => {
                current = null;
                queueMicrotask(change);
                return Promise.resolve();
            });
        });

        afterEach(() => {
            vi.restoreAllMocks();
            Reflect.deleteProperty(document, 'fullscreenEnabled');
            Reflect.deleteProperty(document, 'fullscreenElement');
        });

        function control(): HTMLButtonElement {
            const found = document.querySelector<HTMLButtonElement>('lg-flow-canvas button[data-action="fullscreen"]');
            if (found === null) throw new Error('no fullscreen control');
            return found;
        }

        function expectNodesInsideCanvas(): void {
            const box = document.querySelector('lg-flow-canvas')!.getBoundingClientRect();
            const nodes = Array.from(document.querySelectorAll('lg-flow-node'), (n) => n.getBoundingClientRect());
            expect(nodes.length).toBeGreaterThan(0);
            for (const node of nodes) {
                expect(node.left).toBeGreaterThanOrEqual(box.left - 0.5);
                expect(node.right).toBeLessThanOrEqual(box.right + 0.5);
                expect(node.top).toBeGreaterThanOrEqual(box.top - 0.5);
                expect(node.bottom).toBeLessThanOrEqual(box.bottom + 0.5);
            }
        }

        it('puts canvas, legend and sheet into the full screen and back, refitted each way, and leaves ?stage alone', async () => {
            await open('/rules?stage=FILTER');
            expect(control().getAttribute('aria-pressed')).toBe('false');
            expect((control().getAttribute('aria-label') ?? '').trim()).not.toBe('');
            const before = document.querySelector('lg-flow-canvas')!.getBoundingClientRect().height;

            control().click();
            await settle();
            const area = current as HTMLElement | null;
            expect(area).not.toBeNull();
            for (const part of ['lg-flow-canvas', 'lg-flow-legend', 'lg-stage-sheet']) {
                expect(area!.contains(document.querySelector(part)), part).toBe(true);
            }
            const rect = area!.getBoundingClientRect();
            expect(rect.left).toBeLessThanOrEqual(0.5);
            expect(rect.top).toBeLessThanOrEqual(0.5);
            expect(rect.width).toBeGreaterThanOrEqual(window.innerWidth - 1);
            expect(rect.height).toBeGreaterThanOrEqual(window.innerHeight - 1);
            expect(document.querySelector('lg-flow-canvas')!.getBoundingClientRect().height).toBeGreaterThan(before);
            const legend = document.querySelector('lg-flow-legend')!.getBoundingClientRect();
            expect(legend.bottom).toBeLessThanOrEqual(window.innerHeight + 0.5);
            const sheet = document.querySelector('lg-stage-sheet')!.getBoundingClientRect();
            expect(sheet.top).toBeGreaterThanOrEqual(-0.5);
            expect(sheet.bottom).toBeLessThanOrEqual(window.innerHeight + 0.5);
            expectNodesInsideCanvas();
            expect(control().getAttribute('aria-pressed')).toBe('true');
            expect(params().get('stage')).toBe('FILTER');

            control().click();
            await settle();
            expect(current).toBeNull();
            expect(control().getAttribute('aria-pressed')).toBe('false');
            expect(Math.abs(document.querySelector('lg-flow-canvas')!.getBoundingClientRect().height - before)).toBeLessThan(1);
            expectNodesInsideCanvas();
            expect(params().get('stage')).toBe('FILTER');
            expect(document.querySelector('lg-stage-sheet')).not.toBeNull();
        });

        it('leaves the full screen on Escape without closing the sheet', async () => {
            await open('/rules?stage=FILTER');
            control().click();
            await settle();
            expect(current).not.toBeNull();

            // What the browser does on Escape: the key may reach the page while still in full
            // screen, and the exit's change event follows.
            document.dispatchEvent(new KeyboardEvent('keydown', {key: 'Escape', bubbles: true}));
            current = null;
            change();
            document.dispatchEvent(new KeyboardEvent('keydown', {key: 'Escape', bubbles: true}));
            await settle();

            expect(control().getAttribute('aria-pressed')).toBe('false');
            expect(params().get('stage')).toBe('FILTER');
            expect(document.querySelector('lg-stage-sheet')).not.toBeNull();
            expectNodesInsideCanvas();
        });
    });
});
